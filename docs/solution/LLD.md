# Case Intake — Low-Level Design (backend)

Status: agreed 2026-11-07. Covers the pipeline, fixtures, replay and REST API. The frontend LLD will be added when Q11 (frontend stack) is reopened.
Read [HLD.md](HLD.md) first. Decision numbers (D1…D12) refer to HLD §4.

## 1. Build and runtime

- Maven project at the repo root. `./mvnw test` runs from the root, and every relative path is resolved from the root.
- Java 21. Spring Boot 3.5.x (latest 3.x patch at bootstrap). Artifact `case-intake`, final jar `target/case-intake.jar`.
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation`, Jackson (bundled). Test only: `spring-boot-starter-test` and `com.networknt:json-schema-validator`, used to check outputs against `schema/extraction.schema.json`.
- Base package `com.caseintake`.

### 1.1 Entry modes (`CaseIntakeApplication.main`)

```
args[0] == "extract"              → web-application-type=none, run ExtractCommand
args[0] == "replay"               → web-application-type=none, run ReplayCommand
no args, or args[0] starts "--"   → web server on :8080
any other args[0]                 → usage on stderr, exit 2
```

Command names are matched case-insensitively (`Locale.ROOT`). An option given without a value or with a blank one (`--stage=`, `--out=`) is a configuration error, never a silent default.

| Option | Mode | Meaning |
|---|---|---|
| `--app.catalog=<path>` | all | catalog to use (default `catalog/field_catalog.json`) |
| `--only=D04,D07` | extract, replay | restrict to these documents |
| `--stage=cases\|extract` | extract | re-record only this stage (requires that earlier stages already have fixtures) |
| `--force` | extract | re-record even when fixtures exist |
| `--out=<dir>` | replay | output dir (default `out`); used by tests |

Exit codes: 0 means every document was written. 1 means one or more documents failed (listed on stderr). 2 means a configuration error (unknown command, missing key, blank model id, blank option value, bad catalog).

### 1.2 `application.yml`

```yaml
app:
  catalog: catalog/field_catalog.json
  documents: [documents]          # stretch phase: [documents, documents/stretch]
  fixtures: fixtures
  out: out
llm:
  provider: gemini                # selects the LlmClient bean
  models:
    case-finding: ${LLM_MODEL_CASES:gemini-3.5-flash-lite}
    extraction:   ${LLM_MODEL_EXTRACT:gemini-3.6-flash}
  rate-limit:
    requests-per-minute: 8
  retry:
    max-attempts: 3               # failed attempts (503, timeouts) count against the daily quota
    initial-backoff: 2s
    max-backoff: 60s
  gemini:
    base-url: https://generativelanguage.googleapis.com
    api-key: ${GEMINI_API_KEY:}   # empty is allowed for replay/server, rejected by extract
    timeout: 300s                 # thinking models can take over two minutes under load
```

Bound to `@ConfigurationProperties` records `AppProperties` and `LlmProperties`. Model ids were verified with `GET /v1beta/models` on 2026-11-07. Case finding uses the newest stable Flash-Lite. Extraction uses `gemini-3.6-flash`. The newest stable Flash, `gemini-3.8-flash`, was overloaded (HTTP 503) and its free daily quota was spent on retries. `gemini-3.7-flash` is billed to the same 3.8 quota, so it was no alternative.

## 2. Packages and responsibilities

| Package | Classes | Responsibility |
|---|---|---|
| `catalog` | `Catalog`, `FieldDef`, `FieldType`, `SectionDef`, `OptionDef`, `ValidationRules`, `CatalogLoader`, `CatalogException` | Load and self-check the catalog. The only place field metadata comes from. |
| `validation` | `FieldValidator`, `ValidationResult` | Check one value against one `FieldDef`. |
| `text` | `SourceText`, `QuoteLocator`, `TextSpan` | Code-point view of a document; find quotes. |
| `llm` | `LlmClient`, `LlmRequest`, `LlmResponse`, `LlmException`, `DailyQuotaExhaustedException`, `RateLimiter`, `RetryPolicy`, `LlmProperties` | Provider-neutral model access. |
| `llm.gemini` | `GeminiLlmClient`, `GeminiSchemaTranslator` | Gemini REST adapter. |
| `prompt` | `PromptBuilder`, `ResponseSchemaFactory`, `DocumentFence` | Build prompts and JSON Schemas from the catalog. |
| `fixtures` | `ExchangeKey`, `Exchange`, `ExchangeSource`, `RecordingExchangeSource`, `FixtureExchangeSource`, `FixtureStore`, `Redactor` | Record and replay model exchanges. |
| `extraction` | `CaseFinder`, `CaseFinding`, `CaseCandidate`, `MinimumElement`, `DocumentHints`, `FieldAssembler`, `RawFieldAnswer`, `ConfidencePolicy`, `DocumentFlag` | Turn model JSON into validated cases. |
| `output` | `ExtractionOutput`, `CaseOutput`, `FieldResult`, `Provenance`, `FieldStatus`, `OutputWriter` | Output model and serialisation. |
| `pipeline` | `DocumentSource`, `SourceDocument`, `DocumentPipeline`, `ExtractCommand`, `ReplayCommand`, `RunReport` | Orchestration and CLI. |
| `api` | `CatalogController`, `DocumentController`, `DocumentService`, `DocumentSummary`, `DocumentDetail`, `TitleExtractor`, `ApiExceptionHandler` | REST layer. |

No class names a catalog field id. The only fixed vocabularies in code are the ones the code's logic depends on: `FieldType`, `FieldStatus`, `MinimumElement` and `DocumentFlag`. Confidence levels come from `catalog.confidence_levels`; their list order (high first) defines the ranking.

## 3. Catalog (`catalog`)

```java
record Catalog(String version, List<SectionDef> sections, List<FieldDef> fields,
               List<String> confidenceLevels, Map<String,String> statuses, JsonNode raw) {
  Optional<FieldDef> field(String id);
  List<FieldDef> fieldsInSection(String sectionId);   // catalog order
  String lowerConfidence(String level, int steps);     // clamps at last level
  String minConfidence(String a, String b);
}
record FieldDef(String id, String label, String sectionId, FieldType type,
                List<OptionDef> options, ValidationRules validation, String guidance) {}
record ValidationRules(Optional<Pattern> pattern, Optional<BigDecimal> min, Optional<BigDecimal> max) {}
enum FieldType { STRING, INTEGER, NUMBER, DATE, CODED, CODED_MULTI, STRING_LIST;
                 String json();                        // "coded_multi"
                 static FieldType fromJson(String s); }   // exact lower-case name: "coded_multi" → CODED_MULTI
```

`CatalogLoader.load(Path) → Catalog` throws `CatalogException` with a precise message when:
- `catalog_version` is missing or blank
- a field has an unknown type (the exact lower-case name is required), or a section that doesn't exist
- a field's `validation` is not an object, has an unknown key, has a `pattern` that is not a string or does not compile, or a `min`/`max` that is not a number
- the same field id appears twice
- a coded or coded_multi field has no options
- `confidence_levels` is empty, holds a non-text or blank level, or repeats a level
- `statuses` keys differ from `FieldStatus`

`raw` is the parsed JSON, kept so the API can serve it untouched.

## 4. Validation (`validation`)

`FieldValidator.validate(FieldDef def, JsonNode value) → ValidationResult(boolean ok, String error)`

| Type | Accepts | Rules |
|---|---|---|
| STRING | textual, non-blank | `pattern` must match the whole value |
| INTEGER | integral JSON number (`54`, not `54.5`, not `"54"`) | `min`, `max` |
| NUMBER | JSON number | `min`, `max` |
| DATE | textual `^\d{4}-\d{2}-\d{2}$` and a real calendar date | `pattern` if present |
| CODED | textual and one of the option codes | |
| CODED_MULTI | non-empty array of distinct option codes | when the options include the code `none`, it stands alone |
| STRING_LIST | array of non-blank strings (empty allowed) | `pattern` applied to each item if present |

The validator never changes a value. Error messages name the rule and the value, for example `value 130 above max 120`.

The "stands alone" rule (`FieldValidator.STAND_ALONE_CODE = "none"`) comes from the glossary ("Seriousness": `none` "means no criterion applies and stands alone") and matches `tools/check.py`. It is keyed on the option code, never on a field id, so it applies to any `coded_multi` field that offers `none`; the error is `value ["none","x"] combines "none" with other codes`.

## 5. Source text and quote location (`text`)

`SourceText.of(String documentId, String raw)` holds `int[] codePoints`. `substring(int startCp, int endCp)` returns the original text.
Files are read with `Files.readString(path, UTF_8)`, which keeps `\r\n` exactly as the checker reads it (`newline=""`).

`QuoteLocator.locate(SourceText text, String quote, OptionalInt nearCp) → Optional<TextSpan(int start, int end)>`

Algorithm:
1. **Fold** text and quote the same way. For each code point: lowercase it (`Character.toLowerCase(int)`), map typographic quotes `‘’“”` to `'"`, map en/em dashes to `-`, and turn each run of whitespace into one space. Build `int[] origIndex` so each folded position maps back to its original code point.
2. Trim the folded quote. If it is shorter than **3** code points, return empty (the checker's minimum).
3. Find every occurrence of the folded quote in the folded text.
4. Choose the occurrence nearest to `nearCp` (the case anchor), or the first one if there is no anchor.
5. `start = origIndex[i]`, `end = origIndex[i + len - 1] + 1`.

The output's `Provenance.quote` is `text.substring(start, end)`, the **original** span, not the model's string. That way the checker's `norm(text[start:end]) == norm(quote)` holds by construction.

## 6. LLM access (`llm`, `llm.gemini`)

```java
interface LlmClient { LlmResponse generate(LlmRequest request); String providerId(); LlmCredential credential(); }
record LlmCredential(String secret, String settingName, Set<String> headerNames) { boolean isPresent(); }
record LlmRequest(String model, String systemInstruction, String userContent, JsonNode responseJsonSchema) {}
record LlmResponse(String model, int httpStatus, String rawRequestBody, String rawResponseBody,
                   String requestUrl, String outputText) {}
```

`GeminiLlmClient` (bean when `llm.provider=gemini`):
- Sends `POST {base-url}/v1beta/models/{model}:generateContent` with the header `x-goog-api-key`, so the key is never in the URL.
- The body has `systemInstruction`, `contents[{role:user, parts[{text}]}]` and `generationConfig{temperature:0, responseMimeType:"application/json", responseSchema: GeminiSchemaTranslator.translate(schema)}`.
- `GeminiSchemaTranslator` is the only class that knows Gemini's schema dialect. It maps upper-case types and turns `type:[X,"null"]` into `nullable:true`. It keeps `enum` and `required`, adds `propertyOrdering`, and drops keywords Gemini rejects. If a later Gemini version accepts `responseJsonSchema` directly, the change is confined to this class and the request builder.
- `outputText = candidates[0].content.parts[0].text`. If `finishReason != STOP` it throws `LlmException("finishReason=…")`.
- The API key is checked when the first request is made, not at startup, so replay and server modes start without one.
- `credential()` returns the key, its setting name `GEMINI_API_KEY` (for error messages) and the header `x-goog-api-key` that carries it. Callers outside `llm.gemini` (`ExtractCommand`, `RecordingExchangeSource`, `Redactor`) use only this record and never name the provider.

`RetryPolicy` rejects `max-attempts < 1` and a negative or missing backoff when it is constructed, as `RateLimiter` rejects `requests-per-minute < 1`.

`RateLimiter(int rpm, Clock clock, Sleeper sleeper)` uses a sliding 60-second window per model. `acquire(model)` blocks until a slot is free.

`RetryPolicy` applies to HTTP 429, 500, 503 and I/O timeouts:
- The wait is `delay = max(serverRetryDelay, min(maxBackoff, initial·2^(n-1)) + jitter)`. `serverRetryDelay` is parsed from `error.details[*].retryDelay` (for example `"17s"`).
- A 429 whose `error.details[*].violations[*].quotaId` contains `PerDay` throws `DailyQuotaExhaustedException` straight away, without retrying.
- When attempts run out it throws `LlmException`.

Other 4xx responses are not retried.

## 7. Prompts and schemas (`prompt`)

### 7.1 Document fence (D10)
`DocumentFence.wrap(SourceText) → String`:

```
<<<DOCUMENT {marker}>>>
…raw text…
<<<END DOCUMENT {marker}>>>
```

`marker` is 16 hex characters from `SecureRandom`, generated per request and saved in the fixture with the request.

### 7.2 Stage 1: case finding (fixed schema, not catalog-dependent)

```json
{"type":"object","required":["patients","embedded_instructions","date_convention"],
 "properties":{
  "patients":{"type":"array","items":{"type":"object",
     "required":["label","anchor_quote","patient_quote","reporter_quote","suspect_drug_quote","adverse_event_quote"],
     "properties":{"label":{"type":"string"},
       "anchor_quote":{"type":"string"},
       "patient_quote":{"type":["string","null"]},
       "reporter_quote":{"type":["string","null"]},
       "suspect_drug_quote":{"type":["string","null"]},
       "adverse_event_quote":{"type":["string","null"]}}}},
  "no_case_reason":{"type":["string","null"]},
  "embedded_instructions":{"type":"array","items":{"type":"string"}},
  "date_convention":{"type":"object","required":["value"],
     "properties":{"value":{"enum":["day_first","month_first","unknown"]},
                   "evidence_quote":{"type":["string","null"]}}}}}
```

The system instruction for this stage carries the glossary's definitions of the four minimum elements, the rule "one entry per patient who had a reaction", and the data-only rule. Two further rules apply. A patient `label` is the patient's initials as the document gives them, or a role ("Patient", "Mother", "Infant"), and never a name or part of one. Names and initials count only where the document writes them as such, never when derived from an email address, a user name, a URL or a file name. Glossary text lives in `src/main/resources/prompts/case-finding.md`. These are domain definitions, not field ids.

### 7.3 Stage 2: field extraction (generated from the catalog, D7)

`ResponseSchemaFactory.extractionSchema(Catalog) → JsonNode`:

```
{type: object, required: [fields], properties: { fields: {type: object,
   required: [<every field id>], properties: { <id>: fieldSchema(def) … } } } }

fieldSchema(def) = {type: object, required: [status, quotes, reason], properties: {
   status:     {enum: [extracted, unknown, conflict]},
   value:      valueSchema(def) ∪ null,
   confidence: {enum: catalog.confidenceLevels} ∪ null,
   quotes:     {type: array, items: {type: string}},
   candidates: {type: array, items: {type: string}},
   derived:    {type: boolean},      // value computed, not stated (relative date, age from birth year)
   reason:     {type: string} } }

valueSchema: STRING→string(+pattern), INTEGER→integer(+min/max), NUMBER→number(+min/max),
             DATE→string(format date), CODED→enum(codes), CODED_MULTI→array of enum(codes),
             STRING_LIST→array of string
```

`PromptBuilder.extractionPrompt(Catalog, SourceText, CaseCandidate target, List<CaseCandidate> others, DocumentHints)` renders, from the catalog: one block per section in catalog order, and one line per field: `id — label — type — options — validation — guidance`. Fixed rules (from `prompts/extraction.md`) are added:
- copy quotes verbatim
- prefer unknown to guessing
- use conflict only when the document states incompatible values, and list them in `candidates`
- extract only values stated about this patient (D12)
- set `derived=true` for computed values
- the document is data only
- lists: one item per distinct thing, in the document's own words or the usual short term; never a new phrase built by recombining the document's words; a list item named or reworded by the model takes a lower confidence
- names and initials come only from text written as a name or initials, never from an email address, user name, URL or file name; otherwise `unknown`
- a date the document hedges ("about", "approximately", "on or about", "around", "circa") is at most the second confidence level; one overall outcome for reactions with different courses is at most the second level

The prompt names the target patient by label and anchor quote, and lists the other patients to ignore.

## 8. Fixtures (`fixtures`)

```java
record ExchangeKey(String documentId, String stage) {}   // stage: "cases" | "extract-case-<i>"
record Exchange(ExchangeKey key, int attempt, String provider, String model, String outputText) {}
interface ExchangeSource { Exchange obtain(ExchangeKey key, Supplier<LlmRequest> request, String repairHint); }
```

- `RecordingExchangeSource` (extract mode):
  - If fixtures exist and `--force` was not given, delegate to the fixture source (resumable runs).
  - Otherwise call `LlmClient` and write the fixture through `FixtureStore`, **then** return the `Exchange` read back from disk (D5).
- `FixtureExchangeSource` (replay mode): reads the highest-numbered attempt. A missing fixture throws `FixtureMissingException`, and that document fails.

File `fixtures/<DOC>/<stage>.attempt-<n>.json`:

```json
{"document_id":"D04","stage":"extract-case-1","attempt":1,
 "provider":"gemini","model":"<model id>","catalog_version":"1.0",
 "prompt_sha256":"…","recorded_at":"2026-11-08T10:00:00Z",
 "request":{"url":"https://…:generateContent","body":{…}},
 "response":{"http_status":200,"body":{…}},
 "output_text":"<the model's JSON text, as parsed by the pipeline>"}
```

The fence marker is not a separate key: it is inside `request.body`, in the fenced document text. `prompt_sha256` hashes the full request prompt, so it differs per document and per attempt.

`Redactor.redact(String json, LlmCredential credential) → String` replaces any occurrence of the literal secret and any `key=` query value with `REDACTED`, and removes JSON entries named by one of the credential's `headerNames` (case-insensitive) or a `key` entry holding a plain value. `RecordingExchangeSource` takes the credential from the `LlmClient` it records and refuses to start when it is not set. `FixtureStore.write` asserts that the key is absent after redaction, otherwise it throws and writes nothing.

`ExchangeKey` validates both parts against `DocumentId.PATTERN` (`^[A-Za-z0-9_-]{1,32}$`), the one document-id rule that `DocumentSource` and the REST API also use. Stage keys come from the `Stage` enum (`CASES`: CLI `cases`, key `cases`; `EXTRACT`: CLI `extract`, keys `extract-case-<i>`).

**Repair retry (Q18):** if parsing or schema checking of `outputText` fails, the pipeline calls `obtain` again with `repairHint = "<parse error>"`. That attempt is recorded as `attempt-2`, and replay uses the highest attempt. If attempt 2 also fails, the document fails.

## 9. Extraction (`extraction`)

### 9.1 Case finding
`CaseFinder.find(SourceText, String outputText) → CaseFinding(List<CaseCandidate> cases, DocumentHints hints, EnumSet<DocumentFlag> flags, List<String> notes)`

1. Parse the JSON against the stage 1 shape.
2. For each patient, locate the anchor quote and the four element quotes (`MinimumElement`: PATIENT, REPORTER, SUSPECT_DRUG, ADVERSE_EVENT).
   - A candidate is kept only if **all four** are located.
   - A dropped candidate adds `MISSING_MINIMUM_ELEMENTS` and a note naming the missing element.
3. If no candidates remain:
   - `cases` is empty
   - add `NO_ADVERSE_EVENT` when the event element is the missing one (or `no_case_reason` is set and no patients were listed), otherwise `MISSING_MINIMUM_ELEMENTS`.
4. If more than one candidate remains, add `MULTIPLE_PATIENTS`.
5. If any embedded-instruction quote is located, add `EMBEDDED_INSTRUCTIONS_IGNORED`.
6. `DocumentHints(dateConvention, conventionEvidenceLocated)` takes its `dateConvention` from the response. The hint counts only if its evidence quote is located.

`DocumentFlag` serialises as `no_adverse_event`, `multiple_patients`, `unsupported_format`, `missing_minimum_elements`, `embedded_instructions_ignored`, and later `possible_duplicate`.

### 9.2 Field assembly
`FieldAssembler.assemble(Catalog, JsonNode fieldsNode, SourceText, OptionalInt anchorCp, DocumentHints) → LinkedHashMap<String, FieldResult>`. It iterates the **catalog** fields in order and follows HLD §6 exactly. Notes (exact wording, so tests can assert on them):

| Situation | status | note |
|---|---|---|
| id absent from response | unknown | `Not covered by the recorded model response.` |
| validation fails | unknown | `Model value <v> rejected: <validator error>.` |
| no quote located | unknown | `Model value <v> rejected: quote not found in document.` |
| conflict with fewer than 2 distinct candidates | unknown | `Model reported conflict without two candidates.` |
| conflict | conflict | `Candidates: <c1>; <c2>[; …]` |
| model unknown | unknown | the model's `reason`, if any |
| extracted with confidence capped | extracted | `Confidence lowered: <rule list>.` |

If the model omits `confidence` on an extracted value, it is treated as the **lowest** level, and the note says so. Low, not high, is the safe default.

### 9.3 Confidence policy (D3)
`ConfidencePolicy.apply(FieldDef, JsonNode value, boolean derived, List<TextSpan> spans, SourceText, DocumentHints, String modelConfidence) → Capped(String level, List<String> reasons)`

| Rule | Applies to | Effect |
|---|---|---|
| `DERIVED` | any field with `derived=true` | cap at the second level (medium) |
| `AMBIGUOUS_NUMERIC_DATE` | DATE, when a located quote contains `\b(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})\b` with both parts ≤12 and different | cap at the lowest level (low). If `hints.dateConvention != unknown` and its evidence was located, cap at medium instead. |
| `LITERAL_ABSENT` | INTEGER, NUMBER: the value's plain decimal string isn't in any quote. DATE: the day-of-month isn't a number token in any quote. STRING_LIST: some item isn't written in any quote (case and runs of whitespace ignored) | lower one level |
| `LIST_FROM_MULTIPLE_QUOTES` | STRING_LIST supported by more than one distinct located quote | cap at the second level (medium) |
| `OPTION_NOT_LITERAL` | CODED, CODED_MULTI: no selected option's code, code with `_` read as a space, or label appears as whole words in any quote (case and whitespace folded) | lower one level |

The final level is the minimum of the model's level and every cap.

## 10. Output (`output`)

```java
enum FieldStatus { EXTRACTED, UNKNOWN, CONFLICT }   // JSON: lower case
record Provenance(String quote, int start, int end) {}
record FieldResult(FieldStatus status, JsonNode value, String confidence, List<Provenance> provenance, String note) {}
record CaseOutput(int caseIndex, String label, LinkedHashMap<String, FieldResult> fields) {}
record ExtractionOutput(String documentId, String catalogVersion, String model, List<String> flags, List<CaseOutput> cases) {}
```

`OutputWriter`:
- snake_case property names
- null and absent properties omitted; an empty `provenance` list is omitted too (the output schema requires at least one item whenever the key is present)
- `fields` in catalog order, `flags` sorted
- pretty-printed, UTF-8, trailing newline

`model` is `<provider>/<extraction model>`, or the case-finding model when there are no cases. It is absent for `unsupported_format`. `catalog_version` is always the **current** catalog's version.

## 11. Pipeline (`pipeline`)

`DocumentSource.list(List<Path> dirs) → List<SourceDocument(String id, Optional<Path> txt, List<Path> others)>`. It reads one directory level per configured dir, groups files by basename, and sorts by id.

`DocumentPipeline.process(SourceDocument, Catalog, ExchangeSource) → ExtractionOutput`:
1. If `txt` is absent → `flags=[unsupported_format]`, `cases=[]`, no model call.
2. Case finding, with the stage 1 repair retry.
3. For each kept case `i`: extraction exchange `extract-case-<i>` → `FieldAssembler`, with the repair retry.
4. Return the output. `ExtractCommand` and `ReplayCommand` write it with `OutputWriter` and collect a `RunReport`.

`ReplayCommand` is wired with `FixtureExchangeSource` and never creates an `LlmClient`. `ExtractCommand` is wired with `RecordingExchangeSource`; it fails with exit code 2 when no `LlmClient` is available, when the client's `credential()` is not set (the message names its `settingName`), or when either model id is blank.

## 12. REST API (`api`)

| Endpoint | Handler | Notes |
|---|---|---|
| `GET /api/catalog` | `CatalogController` | returns `catalog.raw()` |
| `GET /api/documents` | `DocumentService.list()` | one `DocumentSummary(id, title, caseCount, flags, reviewCounts)` per `out/*.json`, sorted by id. `reviewCounts = {conflict, unknown, byConfidence: {<catalog level>: n}}` summed over all cases (used by the UI rail; see FRONTEND.md §4.2). |
| `GET /api/documents/{id}` | `DocumentService.get(id)` | `DocumentDetail(id, title, text, extraction)`. `text` is null when there is no .txt |

- `{id}` must match `^[A-Za-z0-9_-]{1,32}$`; anything else gets a 400. An unknown id gets a 404. Both are `ProblemDetail`.
- `TitleExtractor.title(String text)`: the first line matching `^(?i)subject:\s*(.+)$`, otherwise the first non-blank line, trimmed and cut to 120 characters. Null text gives `"(no text layer)"`.
- The service reads files on each request; there is no cache, since the total is about 12 small files.

## 13. Repo scripts

- **`replay.sh [catalog]`** — `cd` to the script dir, `./mvnw -q -DskipTests package` when the jar is missing or older than `src/` or `pom.xml`, then `java -jar target/case-intake.jar replay --app.catalog="${1:-catalog/field_catalog.json}"`. An optional `REPLAY_OUT=<dir>` environment variable adds `--out=<dir>`; a relative dir resolves from the caller's directory. Unset, the outputs go to `out/`. The empty-array expansion is written to be safe under `set -u` on bash 3.2.
- **`start.sh`** — starts the backend (`java -jar …`, built if missing) and `npm --prefix frontend run dev`, and uses `trap` to stop both. Written when the frontend lands.
- **`scripts/score.sh`** — `python3 tools/check.py --catalog catalog/field_catalog.json --answers answers --out out --docs documents`.

## 14. Test inventory (all offline)

| Test class | Proves |
|---|---|
| `CatalogLoaderTest` | Loads the real catalog. Rejects an unknown type, a duplicate id, a coded field with no options, an unknown section. |
| `FieldValidatorTest` | Each type's accept/reject table from §4 |
| `QuoteLocatorTest` | Exact match, case and whitespace folding, `\r\n`, emoji before the quote (code-point offsets), nearest-to-anchor choice, a quote under 3 characters rejected, smart quotes |
| `ResponseSchemaFactoryTest` | Every catalog field present and required. Coded fields become enums of the catalog codes. A field added to the catalog appears in the schema. |
| `GeminiSchemaTranslatorTest` | Nullable mapping, upper-case types, enum preserved |
| `GeminiLlmClientTest` | `MockRestServiceServer`: request shape, the key sent in a header and absent from the URL, `retryDelay` honoured, `PerDay` raises `DailyQuotaExhaustedException`, non-STOP finish reason raises an error |
| `RateLimiterTest`, `RetryPolicyTest` | Fake clock and sleeper |
| `RedactorTest`, `FixtureStoreTest` | The key never written. Highest attempt chosen. |
| `CaseFinderTest` | All four elements → case kept. A missing reporter → no case and a flag. Two patients → `multiple_patients`. Embedded instruction → flag. |
| `FieldAssemblerTest` | Every row of the §9.2 table |
| `ConfidencePolicyTest` | Each rule, and that the minimum of all caps wins |
| `DocumentPipelineTest` | No .txt → `unsupported_format`, no exchange requested. Repair retry recorded as attempt 2. |
| `OutputSchemaConformanceTest` | Every committed `out/*.json` validates against `schema/extraction.schema.json`, and every catalog field is present in every case |
| `ReplayGoldenTest` | Replay into a temp dir equals the committed `out/` as JSON trees |
| `CatalogChangeReplayTest` | A temp catalog adds a new string field and makes the first patterned field's pattern unsatisfiable. Replay gives `unknown` with the exact notes from §9.2. No code change needed. |
| `NoSecretsCommittedTest` | `git ls-files -co --exclude-standard` contains no `AIza[0-9A-Za-z_-]{35}` |
| `DocumentControllerTest` | List, detail, 404, 400 on a bad id, catalog passthrough |
