# Case Intake Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A catalog-driven Java 21 / Spring Boot 3 pipeline that extracts adverse-reaction cases from `documents/*.txt` with Gemini, records every exchange under `fixtures/`, reproduces `out/` offline through `replay.sh`, and serves the results over a REST API.

**Architecture:** One Spring Boot app with three modes (`extract`, `replay`, server). Extraction runs in two stages: case finding, then per-case field extraction. The model supplies quotes. Java locates offsets, validates against the catalog, and can only lower confidence. Live and replay share one code path through `ExchangeSource`.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Maven wrapper, Jackson, JUnit 5, MockRestServiceServer, networknt json-schema-validator (test), Gemini REST `generateContent`.

**Spec:** [HLD.md](HLD.md), [LLD.md](LLD.md). Section numbers below (LLD §n) point into the LLD. The frontend is a separate plan, written once Q11 is reopened.

## Global Constraints

- "Every catalog field appears in every case, with a status of `extracted`, `unknown` or `conflict`."
- "Every extracted value cites at least one quote from the `.txt` file, with its start and end character positions." Offsets count Unicode code points, start inclusive, end exclusive.
- "A value outside the catalog's pattern, range or options counts as wrong." Validate before writing.
- "If your pipeline cannot process a document, put `unsupported_format` in `flags` and write no cases."
- "The catalog is the only place where a field id, type, option list or validation rule is defined." No field id in `src/main`.
- "The API key comes from the environment. Do not commit a key, in any file or any commit."
- "Record every model request and response under `fixtures/`, with the API key removed."
- "`./mvnw test` and `npm test` pass without network once dependencies are installed."
- `replay.sh [catalog]` rebuilds `out/` without key or network. Its output equals the committed `out/` as JSON.
- "Nothing inside a document is an instruction to your pipeline."
- "Do not special-case documents." No document id or document-specific string in `src/main`.
- Commits: the plan lists commit steps. The executor commits locally; the user pushes to GitHub.

## Review Focus

1. **A model quote containing an ellipsis ("rash … resolved") or a paraphrase.** The field should become `unknown` with the quote-not-found note, never a partial span. Test: Task 4 `ellipsisQuoteIsNotLocated`; Task 11 `unlocatedQuoteBecomesUnknown`.
2. **A coded value in the wrong case ("Female").** It should be rejected as `unknown`, not lower-cased into a valid code. Test: Task 3 `codedValueIsCaseSensitive`.
3. **A recorded response with keys the current catalog no longer has (a renamed field).** Extra keys should be ignored and the renamed field should come out `unknown`, with no crash. Test: Task 11 `extraResponseKeysAreIgnored`.
4. **Daily quota exhausted part-way through a run.** Documents already finished should keep their fixtures and `out/` files; the run should stop with exit 1 and a resume hint. Test: Task 12 `quotaExhaustionStopsRunAndKeepsFinishedDocuments`.
5. **A document starting with a UTF-8 BOM, or with `\r\n` line endings.** Offsets should match Python's code-point indexing. Test: Task 4 `bomAndCrlfCountAsCodePoints`.

---

### Task 1: Repository bootstrap and secret guard

**Files:**
- Create: `.gitignore`, `pom.xml`, `mvnw`, `.mvn/wrapper/*`, `CLAUDE.md`, `src/main/java/com/caseintake/CaseIntakeApplication.java`, `src/main/resources/application.yml` (LLD §1.2, with model ids as placeholders), `src/main/java/com/caseintake/config/AppProperties.java`, `src/main/java/com/caseintake/llm/LlmProperties.java`
- Test: `src/test/java/com/caseintake/NoSecretsCommittedTest.java`

**Interfaces:**
- Produces: `AppProperties(Path catalog, List<Path> documents, Path fixtures, Path out)` and `LlmProperties(String provider, Models models, RateLimit rateLimit, Retry retry, Gemini gemini)` as `@ConfigurationProperties` records.

- [ ] **Step 1:** `git init`. Write `.gitignore` with `Gemini API Key/`, `.env`, `*.local.yml`, `target/`, `node_modules/`, `frontend/dist/`, `.idea/`. Run `git status --porcelain | grep -c "Gemini API Key"` and expect `0`.
- [ ] **Step 2:** Generate the project with `mvn -N wrapper:wrapper`. In `pom.xml`, set the Spring Boot 3.5.x parent (the latest 3.x patch on Maven Central), Java 21 and `<finalName>case-intake</finalName>`, and add the dependencies listed in LLD §1.
- [ ] **Step 3: Write the failing test** `NoSecretsCommittedTest.noGoogleApiKeyInCommittableFiles`. It runs `git ls-files -co --exclude-standard` and asserts that no listed file matches `AIza[0-9A-Za-z_\-]{35}`. Add a second test, `gitignoreCoversKeyFolder`, asserting that `git check-ignore -q "Gemini API Key/x"` exits 0.
- [ ] **Step 4:** Run `./mvnw test`. Expect FAIL: the application class doesn't exist yet, so the build has no sources.
- [ ] **Step 5:** Implement `CaseIntakeApplication.main`, which picks the mode from `args[0]` as in LLD §1.1 (the commands are wired in Task 12; until then unknown modes start the server). Write `CLAUDE.md` (under 200 lines): build/test/run commands, a package map copied from LLD §2, and conventions (no field ids in `src/main`, test-first, no key in files).
- [ ] **Step 6:** Run `./mvnw test`. Expect PASS (2 tests).
- [ ] **Step 7:** Commit: `chore: bootstrap Spring Boot project with secret guard`.

### Task 2: Catalog model and loader

**Files:**
- Create: `src/main/java/com/caseintake/catalog/{Catalog,FieldDef,FieldType,SectionDef,OptionDef,ValidationRules,CatalogLoader,CatalogException}.java`
- Test: `src/test/java/com/caseintake/catalog/CatalogLoaderTest.java`, `src/test/java/com/caseintake/support/CatalogFixtures.java` (helpers that write mutated copies of the real catalog to a temp dir)

**Interfaces:**
- Produces: the `Catalog` API in LLD §3: `field(id)`, `fieldsInSection(id)`, `lowerConfidence(level, steps)`, `minConfidence(a, b)`, `raw()`, and `CatalogLoader.load(Path) → Catalog`.

- [ ] **Step 1: Write the failing tests:**
  - `loadsCommittedCatalog`: 16 fields, 4 sections, `confidenceLevels == [high, medium, low]`, and the first field's type is `STRING`. This is asserted by position, without naming ids.
  - `rejectsUnknownType`: the message contains the bad type string.
  - `rejectsDuplicateFieldId`
  - `rejectsCodedFieldWithoutOptions`
  - `rejectsUnknownSection`
  - `lowerConfidenceClampsAtLowest`: `lowerConfidence("medium", 5) == "low"`.
- [ ] **Step 2:** Run `./mvnw test -Dtest=CatalogLoaderTest`. Expect FAIL (compilation).
- [ ] **Step 3:** Implement as LLD §3, using Jackson tree parsing and `FieldType.fromJson`.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(catalog): load and self-check field catalog`.

### Task 3: Field validator

**Files:**
- Create: `src/main/java/com/caseintake/validation/{FieldValidator,ValidationResult}.java`
- Test: `src/test/java/com/caseintake/validation/FieldValidatorTest.java`

**Interfaces:**
- Consumes: `FieldDef`, `FieldType`, `ValidationRules` (Task 2).
- Produces: `FieldValidator.validate(FieldDef, JsonNode) → ValidationResult(boolean ok, String error)`.

- [ ] **Step 1: Write the failing tests.** Build each test's `FieldDef` with synthetic ids, one parameterised table per type from LLD §4:
  - integer: `54` accepted; `54.5`, `"54"` and `121` (max 120) rejected
  - date: `"2026-02-30"` and `"2026-3-4"` rejected
  - pattern `^[A-Z]{1,4}$`: `"M.K."` rejected
  - coded_multi: `[]` and `["x","x"]` rejected
  - string_list: `[]` accepted
  - `codedValueIsCaseSensitive`: options `[female]`, value `"Female"` rejected
- [ ] **Step 2:** Run `./mvnw test -Dtest=FieldValidatorTest`. Expect FAIL.
- [ ] **Step 3:** Implement the LLD §4 table. Error strings follow the form `value <v> above max <max>`.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(validation): catalog-driven value validation`.

### Task 4: Source text and quote locator

**Files:**
- Create: `src/main/java/com/caseintake/text/{SourceText,QuoteLocator,TextSpan}.java`
- Test: `src/test/java/com/caseintake/text/QuoteLocatorTest.java`

**Interfaces:**
- Produces: `SourceText.of(String id, String raw)`, `SourceText.read(String id, Path)`, `substring(int, int)`, `length()` in code points; `QuoteLocator.locate(SourceText, String quote, OptionalInt nearCp) → Optional<TextSpan>`; `record TextSpan(int start, int end)`.

- [ ] **Step 1: Write the failing tests:**
  - `exactMatch`: on D01, `"Mrs M.K."` gives `(276, 284)`, which matches `schema/EXAMPLE-output-D01.json`.
  - `foldsCaseAndWhitespace`: `"54-YEAR-OLD   woman"` gives `(288, 305)`.
  - `emojiBeforeQuoteCountsOneCodePoint`
  - `bomAndCrlfCountAsCodePoints`: the text `"﻿a\r\nbcd"` with quote `"bcd"` gives `(4, 7)`.
  - `picksOccurrenceNearestAnchor`
  - `rejectsQuoteShorterThanThreeChars`
  - `smartQuotesFold`
  - `ellipsisQuoteIsNotLocated`
  - `returnedSpanTextNormalisesToQuote`: for every span in `EXAMPLE-output-D01.json`, the located span's substring equals the file's quote ignoring case and whitespace.
- [ ] **Step 2:** Run `./mvnw test -Dtest=QuoteLocatorTest`. Expect FAIL.
- [ ] **Step 3:** Implement the LLD §5 algorithm, with the folded text and `origIndex` built once per `SourceText` and cached.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(text): code-point quote locator`.

### Task 5: Output model and writer

**Files:**
- Create: `src/main/java/com/caseintake/output/{ExtractionOutput,CaseOutput,FieldResult,Provenance,FieldStatus,OutputWriter}.java`
- Test: `src/test/java/com/caseintake/output/OutputWriterTest.java`, `src/test/java/com/caseintake/support/SchemaAssertions.java` (networknt validator over `schema/extraction.schema.json`)

**Interfaces:**
- Produces: the LLD §10 records, `OutputWriter.write(Path dir, ExtractionOutput)`, `OutputWriter.toJson(ExtractionOutput) → String`, and `SchemaAssertions.assertValid(Path json)`.

- [ ] **Step 1: Write the failing tests:**
  - `exampleRoundTripsAsJsonTree`: parse `EXAMPLE-output-D01.json` into `ExtractionOutput`, write it back, and compare JSON trees.
  - `omitsNullsAndWritesFieldsInGivenOrder`
  - `unsupportedFormatOutputIsSchemaValid`
- [ ] **Step 2:** Run `./mvnw test -Dtest=OutputWriterTest`. Expect FAIL.
- [ ] **Step 3:** Implement with a dedicated `ObjectMapper` (SNAKE_CASE, `NON_NULL`, `INDENT_OUTPUT`) and a trailing newline.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(output): extraction output model and writer`.

### Task 6: Response schemas and prompts from the catalog

**Files:**
- Create: `src/main/java/com/caseintake/prompt/{ResponseSchemaFactory,PromptBuilder,DocumentFence}.java`, `src/main/resources/prompts/case-finding.md`, `src/main/resources/prompts/extraction.md`
- Test: `src/test/java/com/caseintake/prompt/{ResponseSchemaFactoryTest,PromptBuilderTest}.java`

**Interfaces:**
- Consumes: `Catalog`, `SourceText`.
- Produces:
  - `ResponseSchemaFactory.caseFindingSchema() → JsonNode` (LLD §7.2)
  - `ResponseSchemaFactory.extractionSchema(Catalog) → JsonNode` (LLD §7.3)
  - `DocumentFence.wrap(SourceText, String marker) → String`, `DocumentFence.newMarker() → String`
  - `PromptBuilder.caseFindingPrompt(SourceText, String marker) → Prompt(String system, String user)`
  - `PromptBuilder.extractionPrompt(Catalog, SourceText, CaseCandidate target, List<CaseCandidate> others, DocumentHints, String marker, Optional<String> repairHint) → Prompt`

  `CaseCandidate` and `DocumentHints` are defined here as records in `com.caseintake.extraction`, with the fields from LLD §9.1, and Task 10 fills them.

- [ ] **Step 1: Write the failing tests:**
  - `everyCatalogFieldIsRequired`
  - `codedValueIsEnumOfCatalogCodes`: compare against `def.options()` codes.
  - `addedCatalogFieldAppearsInSchemaAndPrompt`: use the `CatalogFixtures` helper to add field `zz_test_field`.
  - `promptListsSectionsInCatalogOrderWithGuidance`
  - `documentIsFencedWithMarker`
  - `promptsContainNoInstructionToFollowDocument`: the system prompt contains the data-only sentence.
- [ ] **Step 2:** Run `./mvnw test -Dtest='ResponseSchemaFactoryTest,PromptBuilderTest'`. Expect FAIL.
- [ ] **Step 3:** Implement. The two `.md` prompt files hold the fixed rule text listed in LLD §7.2–7.3, with the glossary definitions of the four minimum elements copied from `GLOSSARY.md`.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(prompt): catalog-generated response schema and prompts`.

### Task 7: LLM interface and Gemini adapter

**Files:**
- Create: `src/main/java/com/caseintake/llm/{LlmClient,LlmRequest,LlmResponse,LlmException,DailyQuotaExhaustedException}.java`, `src/main/java/com/caseintake/llm/gemini/{GeminiLlmClient,GeminiSchemaTranslator}.java`
- Test: `src/test/java/com/caseintake/llm/gemini/{GeminiSchemaTranslatorTest,GeminiLlmClientTest}.java`

**Interfaces:**
- Produces: LLD §6 types; `GeminiLlmClient` is a bean `@ConditionalOnProperty(llm.provider=gemini)`, and `providerId()` returns `"gemini"`.

- [ ] **Step 1: Write the failing tests:**
  - `nullableUnionBecomesNullableFlag`
  - `typesUpperCased`
  - `enumPreserved`
  - `sendsKeyInHeaderNotUrl`: `MockRestServiceServer` expects header `x-goog-api-key: test-key`, and the URL contains no `key=`.
  - `requestHasTemperatureZeroAndJsonMime`
  - `nonStopFinishReasonThrows`
  - `missingKeyThrowsOnFirstCall`
- [ ] **Step 2:** Run `./mvnw test -Dtest='Gemini*Test'`. Expect FAIL.
- [ ] **Step 3:** Implement with Spring `RestClient`, using the timeout from `LlmProperties.gemini.timeout`.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(llm): provider-neutral client with Gemini adapter`.

### Task 8: Rate limiting and retries

**Files:**
- Create: `src/main/java/com/caseintake/llm/{RateLimiter,RetryPolicy,Sleeper}.java`; Modify: `GeminiLlmClient.java` to wrap calls in `rateLimiter.acquire(model)` and `retryPolicy.execute(...)`
- Test: `src/test/java/com/caseintake/llm/{RateLimiterTest,RetryPolicyTest}.java`, plus 2 cases in `GeminiLlmClientTest`

**Interfaces:**
- Produces: `RateLimiter(int rpm, Clock, Sleeper).acquire(String model)`; `RetryPolicy(LlmProperties.Retry, Sleeper, Random).execute(Supplier<LlmResponse>)`; `interface Sleeper { void sleep(Duration d); }`.

- [ ] **Step 1: Write the failing tests:**
  - `ninthCallInMinuteWaitsForWindow` (rpm 8, fake clock)
  - `honoursServerRetryDelay`: a 429 body with `retryDelay: "17s"` gives a recorded sleep ≥ 17s.
  - `exponentialBackoffCappedAtMax`
  - `perDayQuotaThrowsWithoutRetry`
  - `nonRetryable4xxThrowsImmediately`
  - In `GeminiLlmClientTest`: `retriesThen503ThenSucceeds`.
- [ ] **Step 2:** Run `./mvnw test -Dtest='RateLimiterTest,RetryPolicyTest,GeminiLlmClientTest'`. Expect FAIL.
- [ ] **Step 3:** Implement as LLD §6. Jitter comes from the injected `Random`, so tests are deterministic.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(llm): rate limiter and retry with server-directed backoff`.

### Task 9: Fixtures: store, redaction, exchange sources

**Files:**
- Create: `src/main/java/com/caseintake/fixtures/{ExchangeKey,Exchange,ExchangeSource,RecordingExchangeSource,FixtureExchangeSource,FixtureStore,Redactor,FixtureMissingException}.java`
- Test: `src/test/java/com/caseintake/fixtures/{RedactorTest,FixtureStoreTest,RecordingExchangeSourceTest}.java`

**Interfaces:**
- Consumes: `LlmClient`, `LlmRequest`, `LlmResponse` (Task 7).
- Produces: LLD §8 types. `FixtureStore.write(ExchangeKey, int attempt, FixtureRecord) → Path`, `FixtureStore.latest(ExchangeKey) → Optional<FixtureRecord>`, `FixtureStore.nextAttempt(ExchangeKey) → int`; `Exchange.outputText()` is extracted from the stored response body.

- [ ] **Step 1: Write the failing tests:**
  - `redactsHeaderAndLiteralKey`
  - `storeRefusesToWriteWhenKeyStillPresent`
  - `latestPicksHighestAttempt` (attempts 1 and 2 → 2)
  - `recordingWritesFixtureBeforeReturning`: the returned `Exchange` equals the one read back from disk.
  - `recordingReusesExistingFixtureWithoutForce`: the fake `LlmClient` call count is 0.
  - `replayMissingFixtureThrows`
- [ ] **Step 2:** Run `./mvnw test -Dtest='RedactorTest,FixtureStoreTest,RecordingExchangeSourceTest'`. Expect FAIL.
- [ ] **Step 3:** Implement with the file naming and record layout of LLD §8. `prompt_sha256` is computed over the system and user text.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(fixtures): record and replay model exchanges with key redaction`.

### Task 10: Case finding

**Files:**
- Create: `src/main/java/com/caseintake/extraction/{CaseFinder,CaseFinding,MinimumElement,DocumentFlag}.java`; Modify: `CaseCandidate`, `DocumentHints` (from Task 6) to add the located spans
- Test: `src/test/java/com/caseintake/extraction/CaseFinderTest.java`

**Interfaces:**
- Consumes: `SourceText`, `QuoteLocator`, `Exchange`.
- Produces: `CaseFinder.find(SourceText, String outputText) → CaseFinding(List<CaseCandidate> cases, DocumentHints hints, EnumSet<DocumentFlag> flags, List<String> notes)`. `CaseCandidate(String label, TextSpan anchor, Map<MinimumElement,TextSpan> elements)`. `DocumentFlag.json()` returns the snake_case string.

- [ ] **Step 1: Write the failing tests.** Use D01 text with hand-written stage 1 JSON:
  - `allFourElementsLocatedKeepsCase`
  - `missingReporterQuoteDropsCaseAndFlags`: `missing_minimum_elements`, and the note names `reporter`.
  - `noPatientsWithReasonGivesNoAdverseEventFlag`
  - `twoPatientsGiveMultiplePatientsFlag`
  - `locatedEmbeddedInstructionFlagsDocument`
  - `unlocatedElementQuoteDropsCase`
  - `dateConventionHintNeedsLocatedEvidence`
- [ ] **Step 2:** Run `./mvnw test -Dtest=CaseFinderTest`. Expect FAIL.
- [ ] **Step 3:** Implement LLD §9.1. A malformed JSON shape throws `ResponseShapeException(String detail)`, which Task 12 uses for repair retries.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(extraction): case finding with minimum-element gating`.

### Task 11: Confidence policy and field assembly

**Files:**
- Create: `src/main/java/com/caseintake/extraction/{ConfidencePolicy,FieldAssembler,RawFieldAnswer}.java`
- Test: `src/test/java/com/caseintake/extraction/{ConfidencePolicyTest,FieldAssemblerTest}.java`

**Interfaces:**
- Consumes: `Catalog`, `FieldValidator`, `QuoteLocator`, `DocumentHints`, `FieldResult`.
- Produces: `FieldAssembler.assemble(Catalog, JsonNode fieldsNode, SourceText, OptionalInt anchorCp, DocumentHints) → LinkedHashMap<String, FieldResult>`; `ConfidencePolicy.apply(...) → Capped(String level, List<String> reasons)` (LLD §9.3).

- [ ] **Step 1: Write the failing tests.** In `FieldAssemblerTest`, one test per row of LLD §9.2 asserting the exact note text, plus:
  - `unlocatedQuoteBecomesUnknown`
  - `extraResponseKeysAreIgnored`
  - `outputHasEveryCatalogFieldInCatalogOrder`
  - `missingConfidenceTreatedAsLowest`
  - `provenanceQuoteIsOriginalSpanText`

  In `ConfidencePolicyTest`:
  - `derivedCapsAtMedium`
  - `ambiguousNumericDateCapsAtLow`: the quote `"03/04/2026"` with no hint gives low.
  - `ambiguousDateWithLocatedHintCapsAtMedium`
  - `unambiguousNumericDateNotCapped`: `"15/05/2026"`.
  - `literalAbsentLowersOneLevel`: value `0.25`, quote `"250 mcg"` gives one step down.
  - `minimumOfCapsWins`
- [ ] **Step 2:** Run `./mvnw test -Dtest='ConfidencePolicyTest,FieldAssemblerTest'`. Expect FAIL.
- [ ] **Step 3:** Implement LLD §9.2 and §9.3. A field's `value` is checked by `FieldValidator` before any quote is located.
- [ ] **Step 4:** Run the same command. Expect PASS.
- [ ] **Step 5:** Commit: `feat(extraction): validated field assembly with confidence caps`.

### Task 12: Pipeline, CLI commands, scripts

**Files:**
- Create: `src/main/java/com/caseintake/pipeline/{DocumentSource,SourceDocument,DocumentPipeline,ExtractCommand,ReplayCommand,RunReport}.java`, `replay.sh`, `scripts/score.sh`; Modify: `CaseIntakeApplication.java` (wire the modes)
- Test: `src/test/java/com/caseintake/pipeline/{DocumentSourceTest,DocumentPipelineTest,ExtractCommandTest}.java`

**Interfaces:**
- Consumes: everything above.
- Produces: `DocumentPipeline.process(SourceDocument, Catalog, ExchangeSource) → ExtractionOutput`; `ReplayCommand.run(Path catalog, Path outDir, Set<String> only) → RunReport`; `RunReport(List<String> written, Map<String,String> failed)`, exit code per LLD §1.1.

- [ ] **Step 1: Write the failing tests:**
  - `pdfOnlyDocumentIsUnsupportedFormat`: a temp dir with `X.pdf` only gives `flags=[unsupported_format]`, `cases=[]`, and zero `obtain` calls on a fake `ExchangeSource`.
  - `repairRetryRecordedAsSecondAttempt`: the fake source returns invalid JSON, then valid JSON, and the second call carries a `repairHint`.
  - `secondInvalidResponseFailsDocument`
  - `quotaExhaustionStopsRunAndKeepsFinishedDocuments`: the fake source throws `DailyQuotaExhaustedException` on the 2nd document. The 1st document's output exists, exit code is 1, and stderr contains `--only`.
  - `extractWithoutKeyExitsTwo`
  - `documentSourceGroupsByBasenameAndSorts`
- [ ] **Step 2:** Run `./mvnw test -Dtest='DocumentSourceTest,DocumentPipelineTest,ExtractCommandTest'`. Expect FAIL.
- [ ] **Step 3:** Implement LLD §11. Write `replay.sh` and `scripts/score.sh` from LLD §13, then `chmod +x`.
- [ ] **Step 4:** Run `./mvnw test`. Expect PASS for all suites so far.
- [ ] **Step 5:** Commit: `feat(pipeline): extract and replay commands`.

### Task 13: Live recording run, golden replay, catalog-change test

Needs the user: export `GEMINI_API_KEY` in the shell. The executor never reads the key file.

**Files:**
- Create: `fixtures/**`, `out/D01.json … out/D08.json`, `src/test/java/com/caseintake/{ReplayGoldenTest,CatalogChangeReplayTest,OutputSchemaConformanceTest}.java`; Modify: `application.yml` (model ids), `prompts/*.md` (iterations)

- [ ] **Step 1:** Run `curl -s -H "x-goog-api-key: $GEMINI_API_KEY" https://generativelanguage.googleapis.com/v1beta/models | jq -r '.models[].name'`. Choose the current Flash-Lite id for `case-finding` and the Flash id for `extraction`. Record them in `application.yml` and check the user's per-model limits in AI Studio.
- [ ] **Step 2:** Run `./mvnw -q -DskipTests package && java -jar target/case-intake.jar extract`. Expect exit 0, 8 files in `out/`, and fixtures for every stage.
- [ ] **Step 3:** Run `scripts/score.sh`. Expect a printed score. Inspect any wrong value at high confidence, then change the prompt or rules **generally** (no document-specific text) and re-record only the affected stages: `extract --only=… --stage=… --force`. Stop when no high-confidence wrong values remain or the quota is spent. Save the final checker output for NOTES.md.
- [ ] **Step 4: Write the tests** `ReplayGoldenTest.replayReproducesCommittedOut`, `OutputSchemaConformanceTest.everyOutputIsSchemaValidAndHasEveryField`, and the `CatalogChangeReplayTest` cases:
  - `addedFieldIsUnknownWithNote`
  - `tightenedPatternTurnsValueUnknownWithNote`
  - `replayShAcceptsCatalogArgument`: runs `./replay.sh <tmp catalog>` through `ProcessBuilder`, `--out` to a temp dir.
- [ ] **Step 5:** Run `./mvnw test`. Expect PASS. Then run `./replay.sh && git status --porcelain out/` and expect empty output (byte-identical).
- [ ] **Step 6:** Run `grep -rE 'AIza[0-9A-Za-z_-]{35}' fixtures out` and expect no matches. Commit: `feat: recorded fixtures and outputs for core documents`.

### Task 14: REST API

**Files:**
- Create: `src/main/java/com/caseintake/api/{CatalogController,DocumentController,DocumentService,DocumentSummary,DocumentDetail,TitleExtractor,ApiExceptionHandler}.java`
- Test: `src/test/java/com/caseintake/api/{DocumentControllerTest,TitleExtractorTest}.java`

**Interfaces:**
- Produces: the LLD §12 endpoints and JSON shapes, in camelCase (`caseCount`). The `extraction` property passes through the `out/` JSON unchanged (snake_case).

- [ ] **Step 1: Write the failing tests** (`@WebMvcTest` plus a temp `out/` dir):
  - `listsDocumentsSortedWithCaseCountAndFlags`
  - `detailReturnsTextAndExtraction`
  - `unknownIdIs404ProblemJson`
  - `pathTraversalIdIs400`: `..%2Fpom`
  - `catalogIsServedUnchanged`
  - `titlePrefersSubjectLine`
  - `titleFallsBackToFirstNonBlankLine`
  - `nullTextTitle`
- [ ] **Step 2:** Run `./mvnw test -Dtest='DocumentControllerTest,TitleExtractorTest'`. Expect FAIL.
- [ ] **Step 3:** Implement LLD §12.
- [ ] **Step 4:** Run `./mvnw test`. Expect PASS. Smoke test: `java -jar target/case-intake.jar & sleep 8; curl -s localhost:8080/api/documents | jq length`. Expect `8`.
- [ ] **Step 5:** Commit: `feat(api): catalog and document endpoints`.

### Task 15: README and NOTES skeleton

**Files:**
- Create: `README.md`, `NOTES.md`

- [ ] **Step 1:** `README.md` covers: the one start command (the `./start.sh` placeholder becomes final with the frontend), how to run the pipeline with a key (`export GEMINI_API_KEY=…` then `java -jar … extract`), `./replay.sh`, `./mvnw test`, and the provider/models from `application.yml`.
- [ ] **Step 2:** `NOTES.md` covers:
  - the Task 13 checker output pasted as is
  - per-document notes for D04, D06, D07 and D08 (what is in each, where it's uncertain)
  - decisions D1–D12 with reasons
  - the known limitation (`none` stands alone, not enforced in code)
  - AI tools used, with the design prompts
  - what is missing
- [ ] **Step 3:** Run `./mvnw test && ./replay.sh && git status --porcelain`. Expect tests passing and no diff in `out/`. Commit: `docs: README and NOTES`.

---

## Later phases (separate plans, in this order)

- **F — Frontend** (Q11 on hold): React 19 + TS + Vite, Vitest, `start.sh`, `npx tsc --noEmit`. Needs its own grilling round on the component approach and the design prompt.
- **S1 — Stretch documents:** set `app.documents: [documents, documents/stretch]`, extract S01, S02 and S04 (S03 → `unsupported_format` through the existing Task 12 path), and extend `ReplayGoldenTest` to cover them.
- **S2 — Click quote → field, plus keyboard:** frontend only.
- **S3 — Review actions:** `PATCH /api/documents/{id}/cases/{i}/fields/{fieldId}`, with review state kept in `reviews/<ID>.json` beside `out/` (never mutating `out/`, so replay stays exact), and per-document progress.
- **S4 — Duplicates:** a deterministic comparison of extracted fields across documents (initials, age, sex, suspect drug, onset). The comparison keys are chosen by catalog section, not field id. The result goes to `out/duplicates.json`, and the UI gets a side-by-side diff. Expected pair from the survey: S01 and S02. Decoys: D01/D06/S04.
