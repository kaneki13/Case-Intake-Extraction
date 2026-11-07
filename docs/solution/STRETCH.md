# Stretch items — design and implementation plan

Status: agreed in the stretch grilling rounds (2026-11-07/08). Branch `stretch`, cut from `Extraction-Pipeline` at `2f13262`.
It builds on [HLD.md](HLD.md), [LLD.md](LLD.md) and [FRONTEND.md](FRONTEND.md). Section numbers like LLD §9.3 point into those documents.

## 1. Decisions

| # | Decision |
|---|---|
| Q1 | **Image-only PDFs are transcribed first, then extracted as text.** A document that has a `.pdf` but no `.txt` goes to a new `transcribe` stage. That stage sends the PDF to a vision model and asks for a faithful plain-text transcription: `[illegible]` where unreadable, no corrections, no guesses. The transcript is written to `derived/<ID>.txt` and becomes the document's source text. Case finding and extraction then run unchanged on it, so offsets, validation, confidence rules, replay and UI highlighting all keep working. The document gets the flag `transcribed_from_image`. A generic rule caps every value from a transcribed source at the second confidence level. An empty or failed transcription gives `unsupported_format`. |
| Q2 | Transcription model: `llm.models.transcription` (`gemini-3.6-flash`). The PDF is sent inline as base64 (`inline_data`, `application/pdf`). `app.documents` gains `documents/stretch`. A full stretch run costs about 9 calls. |
| Q3 | **Duplicates are found deterministically after all documents are processed.** Every case is compared with every other case in another document, field by field over the catalog. Any contradiction in the identity section rules a pair out. A pair is a duplicate when it has at least N agreeing fields and at least one agreement in each required section. The section ids and thresholds live in `application.yml`; no field ids are written in code. Groups are built by union-find. Confidence and a reason string are generated. The result is `out/duplicates.json`, which replay rebuilds. |
| Q4 | `out/duplicates.json` matches `schema/duplicates.schema.json` exactly. `GET /api/duplicates` adds the matched case pair, which the server recomputes with the same detector. |
| Q5 | Route `/duplicates` lists the groups. `/duplicates/:index` shows three columns: document A's text, a comparison table, and document B's text. A UI comparator gives each field one of `match`, `differ`, `one-sided` or `both-absent`, each with its own CSS class, icon and text. Clicking a row highlights that field's quote in both texts. The document rail gets a "Duplicates (n)" link. |
| Q6 | **Reviews are stored in `reviews/<ID>.json`, which is git-ignored.** Writes are atomic (temp file, then rename). `out/` is never touched, so replay and the schema stay intact. The API merges reviews into its responses. |
| Q7 | PATCH body is `{action: accept\|reject\|edit\|reset, value?}`. An edit is validated against the catalog and returns 400 (problem+json) if it fails. Unknown ids return 404. `reset` undoes a review. |
| Q8 | Progress is `fields_reviewed / total_fields × 100` over all cases of a document. Reviewed means accepted, rejected or edited. The header shows a bar and the rail shows a ring per document. A document with no cases shows "Nothing to review". |
| Q9 / Q13–Q18 | **Keyboard model.** ↑/↓ move between fields only while focus is inside the fields pane; normal scrolling works in both panes. On a selected field, Enter or typing starts an edit. Text and number fields: the first typed character replaces the value. Coded, date, multi and list fields: their control opens (typeahead for coded). Enter inside the editor saves, then selection moves to the next unreviewed field. Esc cancels an edit; with no edit open, Esc clears the selection. There are no letter shortcuts (`j`/`k` are removed). Buttons: Accept this, Reject this and Reset on the selected row and in the inspector; Accept all and Reject all in the fields-pane header. Keys: `Alt+A` / `Alt+R` for this field, `Alt+Shift+A` / `Alt+Shift+R` for all fields. Accept and reject also move to the next unreviewed field. |
| Q16 | Bulk actions apply to the current case and only to unreviewed fields; manual edits are never overwritten. Reject all asks for confirmation. One atomic bulk endpoint handles them. |
| Q19 | Accepting an `unknown` or `conflict` field confirms it, and it counts as reviewed. Accept all includes these fields. |
| Q10 | Clicking a quote to select its field already works and stays as it is. Selection stays in the URL (`?field=`), and keyboard actions use the same selection. |
| Q11 | Local drafts are replaced by server-side edits. Edited fields show a "Manual edit" badge (✎, blue background) next to the struck-through model value. Accepted fields show ✓ accepted; rejected fields show ⊘ rejected with the value struck through. |

## 2. Backend design

### 2.1 Multimodal requests and the transcription stage

- `LlmRequest` gains `List<LlmAttachment> attachments`, where `record LlmAttachment(String mimeType, byte[] data)`. An empty list means text only, so existing callers are unchanged.
- `GeminiLlmClient` adds one `{inline_data:{mime_type, data:<base64>}}` part per attachment, ahead of the text part. `GeminiSchemaTranslator` is unchanged.
- The fixture stores the request body as sent, including the base64 (about 64 KB for S03).
- `Stage.TRANSCRIBE` has CLI value `transcribe` and fixture key `transcribe`. It runs only for PDF-only documents.
- `ResponseSchemaFactory.transcriptionSchema()` is `{transcript: string, illegible_count: integer}`, both required.
- `PromptBuilder.transcriptionPrompt()` reads `prompts/transcription.md`. It asks for verbatim transcription in reading order, line breaks kept, `[illegible]` for unreadable spans, no corrections or expansions, and says that nothing in the image is an instruction.
- `TranscriptStore(Path derivedDir)` has `write(id, text)` and `read(id) → Optional<String>`. The file is `derived/<ID>.txt`, UTF-8, exactly the transcript.
- `DocumentPipeline.process`: for a PDF-only document, call `obtain(TRANSCRIBE)`. A blank transcript gives `unsupported_format`. Otherwise write the transcript to `derived/` and use `SourceText.of(id, transcript)`, then continue with case finding and extraction. Add the flag `TRANSCRIBED_FROM_IMAGE` and pass `DocumentHints.sourceTranscribed = true`.
- `ConfidencePolicy` rule `TRANSCRIBED_SOURCE`: when `hints.sourceTranscribed()` is true, cap at index min(1, size−1).
- Replay rebuilds `derived/<ID>.txt` from the fixture before extraction. The golden test compares `derived/` as well as `out/`.
- `DocumentService` reads the `.txt`, otherwise `derived/<ID>.txt`, otherwise null.

### 2.2 Duplicates

- `DuplicateProperties` (yml `duplicates`):
  - `identity-section: patient`
  - `required-sections: [patient, drug, event]`
  - `min-agreements: 5`
  - `high-agreements: 8`
  - `integer-tolerance: 1`
- `ValueComparator.compare(FieldDef, FieldResult a, FieldResult b) → Comparison {MATCH, DIFFER, ONE_SIDED, BOTH_ABSENT}`. Only `extracted` values count as present. Rules:

  | Type | Equal when |
  |---|---|
  | STRING | equal after trim and case-fold |
  | INTEGER | difference ≤ `integer-tolerance` |
  | NUMBER | numerically equal |
  | DATE | equal |
  | CODED | equal |
  | CODED_MULTI | equal as sets |
  | STRING_LIST | both empty, or overlapping after trim and case-fold |

- `DuplicateDetector.detect(List<ExtractionOutput>, Catalog, DuplicateProperties) → DuplicateReport(List<DuplicateGroup> groups, List<CasePair> pairs)`.
  - Compare every case pair from different documents.
  - Reject a pair if any field in the identity section is `DIFFER`.
  - Accept a pair when agreements ≥ `min-agreements` and every required section has at least one `MATCH`.
  - Confidence is `high` when agreements ≥ `high-agreements`, otherwise `medium`.
  - Build groups with union-find over accepted pairs, sorted by document id.
  - The reason is generated from the matched values, in catalog order, up to 6 of them, for example `same: EW; 81; female; digoxin; 2026-01-05; …`.
- `DuplicatesWriter` writes `out/duplicates.json` as `{groups:[{document_ids, confidence, reason}]}`. The file is always written, with an empty `groups` list when there are none. `ExtractCommand` and `ReplayCommand` run the detector after all documents. Replay compares this file as well.

### 2.3 Reviews

- `ReviewStore(Path reviewsDir)`: `load(id) → DocumentReviews` and `save(id, DocumentReviews)`. Saves are atomic (temp file, then `ATOMIC_MOVE`) and serialised per document with a lock.
- `record FieldReview(ReviewState state, JsonNode value, Instant at)` where `ReviewState {ACCEPTED, REJECTED, EDITED}`. `DocumentReviews` is `Map<Integer caseIndex, Map<String fieldId, FieldReview>>`.
- `ReviewService` (with an injected `Clock`):
  - `apply(id, caseIndex, fieldId, ReviewRequest) → MergedField`
  - `applyBulk(id, caseIndex, BulkReviewRequest) → List<MergedField>`, applied to unreviewed fields only, under the same lock.
  - `progress(id) → Progress(int reviewed, int total)`
  - Validation:
    - The document id uses the shared `DocumentId` pattern.
    - The case index must be within the output's cases.
    - The field id must be in the catalog.
    - An edit value goes through `FieldValidator`.
  - Reviews for case indexes or field ids that no longer exist are ignored when merging and when counting progress.
- `record MergedField(String fieldId, JsonNode model, FieldReview review)`, where `model` is the `out/` field result unchanged.
- Endpoints:
  - `PATCH /api/documents/{id}/cases/{caseIndex}/fields/{fieldId}` with body `{action, value?}` returns `MergedField`.
  - `PATCH /api/documents/{id}/cases/{caseIndex}/fields` with body `{action: accept|reject, fieldIds?: [..]}` returns `MergedField[]`. Without `fieldIds` it applies to every unreviewed field.
  - `GET /api/documents/{id}` gains `reviews` (the `DocumentReviews` JSON) and `progress`.
  - `GET /api/documents` gains `progress` per document.
- `reviews/` and `derived/`: `reviews/` is git-ignored. `derived/` is committed, because it is graded material that replay rebuilds.

## 3. Frontend design

- **API layer:** `api/types.ts` gains `FieldReview`, `Progress`, `DuplicateGroupView`. `api/reviews.ts` has `useReviewField()` and `useBulkReview()` (TanStack mutations that invalidate `['document', id]` and `['documents']`). `useDuplicates()` loads the groups.
- **Review UI:**
  - `ReviewButtons` (Accept this / Reject this / Reset) appears on the selected row and in the inspector.
  - `BulkReviewBar` (Accept all / Reject all, with a confirmation dialog using native `<dialog>`) sits in the fields-pane header with a `ProgressBar`.
  - The top bar shows the current document's progress. The rail shows a `ProgressRing`.
  - Badges: ✓ accepted, ⊘ rejected (value struck through), ✎ Manual edit (blue background, with the model value struck through below it).
  - `lib/drafts.ts` is removed. Saving an edit sends `edit`.
- **Keyboard (`useFieldKeyboard`, rewritten):**
  - ↑/↓ act only when `event.target` is inside `#fields`.
  - Enter or a printable key starts an edit on the selected row. Text and number seed the editor with the typed character; the other types open their control.
  - Inside the editor, Enter saves and Esc cancels. With no editor open, Esc clears the selection.
  - `Alt+A` / `Alt+R` act on this field; `Alt+Shift+A` / `Alt+Shift+R` act on all fields.
  - The existing ignore rules (open menus and dialogs, modifiers other than Alt) still apply.
  - After a save, accept or reject, the selection moves to the next unreviewed visible field.
- **Duplicates UI:**
  - `lib/compare.ts` has `compareFieldValues(field, a, b) → 'match'|'differ'|'one-sided'|'both-absent'`, using the same rules as `ValueComparator`, and `comparisonClass(state)`.
  - `DuplicatesPage` lists the groups. `DuplicateCompare` is the three-column view.
  - `DocumentTextPane({detail, caseIndex, fieldId})` is a prop-driven variant of the document view, so both sides highlight the same field.
  - Each state is shown with an icon and text as well as colour: ✓ green for match, ✗ red for differ, ◌ neutral for one-sided, · muted for both-absent.

## 4. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Process the stretch documents, including transcribing the image-only S03, detect duplicate cases into `out/duplicates.json` with a side-by-side review screen, and add persistent review actions with progress and a keyboard-driven editing model.

**Architecture:** On the backend, a transcription stage runs in front of the existing two-stage pipeline. A pure duplicate detector runs after all documents are processed. A review store sits beside `out/`, and new REST endpoints expose it. On the frontend, TanStack mutations drive the review state, the keyboard hook is rewritten, and a duplicates route compares two documents.

**Tech Stack:** As before: Java 21, Spring Boot 3.5, Gemini REST with `inline_data`; React 19 + TypeScript, TanStack Query, Radix.

**Spec:** this document §1–§3, and LLD/FRONTEND for everything that is unchanged.

### Global Constraints

- `replay.sh` reproduces `out/` (including `duplicates.json`) and `derived/` byte for byte, with no key and no network.
- `out/*.json` matches `schema/extraction.schema.json`, and `out/duplicates.json` matches `schema/duplicates.schema.json` exactly.
- Reviews never modify `out/`. `reviews/` is git-ignored.
- No catalog field id or document-specific text appears in `src/main` or `frontend/src`. Section ids for duplicates come from configuration only.
- No silent fallbacks: invalid input is a 400 or 404 problem+json, and pipeline failures follow the exit codes in LLD §1.1.
- `./mvnw test`, `npm --prefix frontend test` and `npx tsc --noEmit` pass offline.
- The key is never printed or written to any file. The secret greps over `fixtures`, `out` and `derived` must find nothing.
- Commit with explicit pathspecs only. Never push.

### Review Focus

1. **Two PATCH requests on the same document at almost the same time** (double-click, or bulk plus single): both reviews persist and the file stays valid JSON. Test in S6: `concurrentPatchesKeepBothReviews`.
2. **Stored reviews for a field id or case that no longer exists** (catalog or output changed): ignored in the merge and in progress, no crash. Test in S6: `staleReviewsAreIgnored`.
3. **A transcript containing instruction-like text or the fence line:** it is fenced like any other text, and a fence collision fails loudly. Test in S2: `transcriptIsFencedLikeText`.
4. **Typing an edit while Accept all is in flight:** the edit survives, because bulk applies to unreviewed fields only, on the server. Test in S6: `bulkDoesNotOverwriteEdits`. Test in S8: `editSurvivesBulkAccept`.
5. **↑/↓ pressed while focus is in the document text:** the document scrolls and the selection stays put. Test in S9: `arrowsOutsideFieldsPaneDoNotNavigate`.

---

### Task S1: Multimodal LLM requests

**Files:** Create `llm/LlmAttachment.java`. Modify `llm/LlmRequest.java`, `llm/gemini/GeminiLlmClient.java` and every `new LlmRequest(` call site (pass `List.of()`). Test: `GeminiLlmClientTest`.

**Interfaces:** Produces `record LlmAttachment(String mimeType, byte[] data)` and `LlmRequest(model, systemInstruction, userContent, responseJsonSchema, List<LlmAttachment> attachments)`.

- [ ] **Step 1:** Write failing tests:
  - `attachmentBecomesInlineDataPartBeforeText`: the body's `contents[0].parts[0].inline_data` has `mime_type` `application/pdf` and data equal to `Base64(bytes)`, and `parts[1].text` is the user text.
  - `noAttachmentsKeepsSingleTextPart`.
- [ ] **Step 2:** `./mvnw -q test -Dtest=GeminiLlmClientTest`. Expect FAIL (compilation).
- [ ] **Step 3:** Implement. Encode with `java.util.Base64`.
- [ ] **Step 4:** Run the full `./mvnw -q -o test` and confirm `./replay.sh && git status --porcelain out/` is empty. Expect green and empty.
- [ ] **Step 5:** Commit `feat(llm): inline attachments for multimodal requests`.

### Task S2: Transcription stage for image-only PDFs

**Files:**
- Create `pipeline/TranscriptStore.java` and `src/main/resources/prompts/transcription.md`.
- Modify `pipeline/Stage.java` (add TRANSCRIBE), `pipeline/DocumentPipeline.java`, `prompt/{PromptBuilder,ResponseSchemaFactory}.java`, `extraction/{DocumentHints,DocumentFlag,ConfidencePolicy}.java`, `config/AppProperties.java` (`derived: derived`), `llm/LlmProperties.java` (`models.transcription`), `application.yml`, `api/DocumentService.java` (fall back to the derived text), and `docs/solution/LLD.md` (§1.2, §7, §9.3, §11).
- Tests: `DocumentPipelineTest`, `ConfidencePolicyTest`, `TranscriptStoreTest`, `DocumentControllerTest`.

**Interfaces:**
- Consumes: S1 `LlmAttachment`.
- Produces:
  - `Stage.TRANSCRIBE` (`"transcribe"`)
  - `TranscriptStore.write(String id, String text)` and `read(String id) → Optional<String>`
  - `DocumentFlag.TRANSCRIBED_FROM_IMAGE` (`transcribed_from_image`)
  - `DocumentHints(DateConvention, boolean evidenceLocated, boolean sourceTranscribed)`
  - confidence rule `TRANSCRIBED_SOURCE`

- [ ] **Step 1:** Write failing tests using a fake `ExchangeSource`:
  - `pdfOnlyDocumentIsTranscribedThenExtracted`: the exchange keys called are `transcribe`, `cases`, `extract-case-0` in that order; `derived/X.txt` equals the transcript; flags contain `transcribed_from_image`; provenance offsets index the transcript.
  - `blankTranscriptIsUnsupportedFormat`: no case-finding call is made.
  - `transcriptIsFencedLikeText`.
  - `replayRebuildsDerivedTextFromFixture`.
  - `ConfidencePolicyTest.transcribedSourceCapsAtSecondLevel`.
  - `DocumentControllerTest.detailServesDerivedTextWhenNoTxt`.
- [ ] **Step 2:** `./mvnw -q test -Dtest='DocumentPipelineTest,ConfidencePolicyTest,TranscriptStoreTest,DocumentControllerTest'`. Expect FAIL.
- [ ] **Step 3:** Implement per §2.1. The transcription request uses `models.transcription`, with the PDF bytes as the attachment and an empty fence (no document text is in the prompt). The user text says: "The attached file is the document."
- [ ] **Step 4:** Run the full `./mvnw -q -o test` and the `replay.sh` check. Expect green and an unchanged `out/`; the core documents have `.txt` files, so S2 does not touch them.
- [ ] **Step 5:** Commit `feat(pipeline): transcribe image-only PDFs before extraction`.

### Task S3: Process the stretch documents (live; needs the key and quota)

**Files:** `application.yml` (`app.documents: [documents, documents/stretch]`), `fixtures/S0*/**`, `out/S0*.json`, `derived/S03.txt`, `ReplayGoldenTest` (12 documents plus `derived/`), `NoSecretsCommittedTest` (also scan `derived/`).

- [ ] **Step 1:** Run `java -jar target/case-intake.jar extract --only=S01,S02,S03,S04` (about 9 calls). If quota runs out, stop and report the remaining ids.
- [ ] **Step 2:** Inspect `derived/S03.txt` against `documents/stretch/S03.pdf` (read the PDF image). Every `[illegible]` must be justified, and there must be no invented text. If the transcription prompt needs a fix, make it general and re-record with `--only=S03 --force` (cases and extract are re-recorded too).
- [ ] **Step 3:** Update `ReplayGoldenTest` to expect 12 outputs and an identical `derived/`. Then run `./mvnw -q -o test`, `./replay.sh`, `git status --porcelain out/ derived/` (expect empty), and the secret greps.
- [ ] **Step 4:** Commit `feat: stretch documents incl. transcribed S03` using an explicit pathspec.

### Task S4: Deterministic duplicate detection

**Files:** Create `duplicates/{DuplicateProperties,ValueComparator,Comparison,DuplicateDetector,DuplicateReport,DuplicateGroup,CasePair,DuplicatesWriter}.java`. Modify `pipeline/{ExtractCommand,ReplayCommand}.java` (run after all documents), `application.yml`, `ReplayGoldenTest` (compare `duplicates.json`), and `docs/solution/LLD.md` (new §15). Tests: `ValueComparatorTest`, `DuplicateDetectorTest`, `DuplicatesWriterTest`.

**Interfaces:**
- Produces:
  - `ValueComparator.compare(FieldDef, FieldResult, FieldResult) → Comparison`
  - `DuplicateDetector.detect(List<ExtractionOutput>, Catalog, DuplicateProperties) → DuplicateReport`
  - `record CasePair(String docA, int caseA, String docB, int caseB, int agreements, String confidence, String reason)`
  - `DuplicatesWriter.write(Path outDir, DuplicateReport)`

- [ ] **Step 1:** Write failing tests:
  - `ValueComparatorTest`: one test per type row in §2.2, plus `unknownVsExtractedIsOneSided` and `bothUnknownIsBothAbsent`.
  - `DuplicateDetectorTest`, using synthetic outputs built from the real catalog by section, with no ids written in the test:
    - `sameCaseInTwoDocumentsIsGrouped`
    - `identityContradictionVetoes` (the D01/S04 shape: same drug, reaction, age and sex, but different initials)
    - `missingRequiredSectionAgreementRejects`
    - `threeWayGroupViaUnionFind`
    - `confidenceHighAtHighAgreements`
    - `reasonListsMatchedValuesInCatalogOrder`
  - `DuplicatesWriterTest.outputValidatesAgainstDuplicatesSchema`, which also checks that an empty `groups` list is still written.
- [ ] **Step 2:** `./mvnw -q test -Dtest='ValueComparatorTest,DuplicateDetectorTest,DuplicatesWriterTest'`. Expect FAIL.
- [ ] **Step 3:** Implement per §2.2. Required sections and the identity section are read from `DuplicateProperties`; a section id that is not in the catalog raises a configuration error (exit 2).
- [ ] **Step 4:** Run `./replay.sh` and check that `out/duplicates.json` has exactly one group `["S01","S02"]`. If S3 has not run yet, check it against `[]` and leave the assertion to S3's golden test. Then run `./mvnw -q -o test`. Expect green.
- [ ] **Step 5:** Commit `feat(duplicates): deterministic case matching and duplicates.json` (including `out/duplicates.json`).

### Task S5: Duplicates API

**Files:** Create `api/DuplicatesController.java` and `api/DuplicateGroupView.java`. Test: `DuplicatesControllerTest`.

**Interfaces:** Produces `GET /api/duplicates → [{documentIds, confidence, reason, pair:{a:{id, caseIndex}, b:{id, caseIndex}}}]`, recomputed with `DuplicateDetector` over `out/`.

- [ ] **Step 1:** Write failing tests:
  - `listsGroupsWithMatchedCasePair`
  - `emptyWhenNoDuplicates`
  - `agreesWithCommittedDuplicatesJson`: the document ids and confidence equal the content of `out/duplicates.json`.
- [ ] **Step 2:** Run the tests. Expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the full suite. Expect green.
- [ ] **Step 5:** Commit `feat(api): duplicates endpoint`.

### Task S6: Review store, PATCH endpoints and progress

**Files:**
- Create `review/{ReviewState,FieldReview,DocumentReviews,ReviewStore,ReviewService,ReviewRequest,BulkReviewRequest,MergedField,Progress}.java` and `api/ReviewController.java`.
- Modify `api/{DocumentService,DocumentDetail,DocumentSummary}.java`, `config/AppProperties.java` (`reviews: reviews`), `.gitignore` (`reviews/`), and `docs/solution/LLD.md` (§12).
- Tests: `ReviewStoreTest`, `ReviewServiceTest`, `ReviewControllerTest`.

**Interfaces:**
- Produces the endpoints in §2.3.
- `DocumentDetail` gains `reviews` and `progress`; `DocumentSummary` gains `progress`. `Progress(int reviewed, int total)`.

- [ ] **Step 1:** Write failing tests:
  - `acceptRejectEditResetRoundTrip`
  - `editIsValidatedAgainstCatalog400`
  - `unknownFieldOrCase404`
  - `bulkAcceptAppliesToUnreviewedOnly`
  - `bulkDoesNotOverwriteEdits`
  - `concurrentPatchesKeepBothReviews` (two threads, one store)
  - `staleReviewsAreIgnored`
  - `outDirectoryIsNeverModified` (hash `out/` before and after)
  - `progressCountsAllCasesAndCatalogFields`
  - `gitignoreCoversReviews`
- [ ] **Step 2:** Run the tests. Expect FAIL.
- [ ] **Step 3:** Implement per §2.3, with atomic writes and a per-document lock.
- [ ] **Step 4:** Run the full suite and confirm `./replay.sh` leaves `out/` unchanged. Expect green.
- [ ] **Step 5:** Commit `feat(review): review store, PATCH endpoints and progress`.

### Task S7: Frontend API for reviews and duplicates

**Files:** Modify `frontend/src/api/{types,client,queries}.ts`. Create `frontend/src/api/reviews.ts`. Update `frontend/test/msw/handlers.ts` and `fixtures.ts` with stateful PATCH handlers and a duplicates fixture built from two clones of D01 with one changed identity value, so it differs. Test: `frontend/test/reviewsApi.test.ts`.

- [ ] **Step 1:** Write failing tests:
  - `patchSendsActionAndValue`
  - `bulkSendsFieldIdsOrAll`
  - `mutationInvalidatesDocumentAndList`
  - `problemJson400SurfacesValidationMessage`
  - `useDuplicatesReturnsGroups`
- [ ] **Step 2:** `npm --prefix frontend test -- reviewsApi`. Expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the full `npm --prefix frontend test` and `typecheck`. Expect green.
- [ ] **Step 5:** Commit `feat(ui): review and duplicates API layer`.

### Task S8: Review buttons, badges, bulk actions and progress UI

**Files:**
- Create `components/{ReviewButtons,BulkReviewBar,ProgressBar,ProgressRing,ConfirmDialog}.tsx` and their CSS modules.
- Modify `FieldRow.tsx`, `FieldInspector.tsx`, `FieldsPanel.tsx`, `SummaryBar.tsx` (replace the edited chip with accepted / rejected / edited counts), `AppShell.tsx` (header progress), `DocumentRail.tsx` (ring).
- Delete `lib/drafts.ts` and its tests; switch the controls' save path to the `edit` mutation.
- Tests: `frontend/test/review.test.tsx`.

- [ ] **Step 1:** Write failing tests:
  - `acceptThisMarksRowAcceptedAndAdvances`
  - `rejectThisStrikesValueAndShowsRejectedBadge`
  - `saveEditShowsManualEditBadgeWithModelValueStruck`
  - `resetReturnsToUnreviewed`
  - `rejectAllAsksForConfirmation`
  - `acceptAllSkipsReviewedFields`
  - `editSurvivesBulkAccept`
  - `headerProgressShowsReviewedOverTotal`
  - `noCaseDocumentShowsNothingToReview`
  - `railShowsProgressRing`
- [ ] **Step 2:** `npm --prefix frontend test -- review`. Expect FAIL.
- [ ] **Step 3:** Implement per §3. Every badge has icon, colour and text, and every button has an accessible name and an `Alt` shortcut tooltip.
- [ ] **Step 4:** Run the full test suite and `typecheck`. Expect green.
- [ ] **Step 5:** Commit `feat(ui): review actions, badges, bulk review and progress`.

### Task S9: Keyboard editing model

**Files:** Rewrite `hooks/useFieldKeyboard.ts`. Modify `FieldRow.tsx`, `FieldInspector.tsx` and `controls/*` (seeded edit, open-on-Enter, save on Enter, cancel on Esc). Update `frontend/README.md` (shortcut table). Tests: rewrite `frontend/test/keyboard.test.tsx`.

- [ ] **Step 1:** Write failing tests:
  - `arrowsNavigateOnlyInsideFieldsPane`
  - `arrowsOutsideFieldsPaneDoNotNavigate`
  - `enterStartsEditAndEnterAgainSaves`
  - `typingOnTextFieldSeedsEditorWithCharacter`
  - `typingOnCodedFieldOpensSelectWithTypeahead`
  - `escCancelsEditWithoutReviewing`
  - `escWithoutEditClearsSelection`
  - `altAAcceptsAltRRejectsThisField`
  - `altShiftRRejectAllAsksConfirmation`
  - `jAndKNoLongerNavigate`
  - `saveAdvancesToNextUnreviewed`
- [ ] **Step 2:** `npm --prefix frontend test -- keyboard`. Expect FAIL.
- [ ] **Step 3:** Implement per §3 (Keyboard).
- [ ] **Step 4:** Run the full test suite and `typecheck`. Expect green.
- [ ] **Step 5:** Commit `feat(ui): keyboard editing model`.

### Task S10: Duplicate review screen

**Files:** Create `lib/compare.ts`, `components/{DuplicatesPage,DuplicateCompare,DocumentTextPane}.tsx` with CSS modules. Modify `routes.tsx` and `DocumentRail.tsx` (add the "Duplicates (n)" link). Tests: `frontend/test/compare.test.ts`, `frontend/test/duplicates.test.tsx`.

- [ ] **Step 1:** Write failing tests:
  - `compareFieldValues` cases mirroring the §2.2 table, plus `comparisonClassIsDistinctPerState`.
  - `duplicatesListShowsGroupsWithReasonAndConfidence`
  - `compareViewShowsBothTextsAndAllCatalogFields`
  - `matchRowsGreenDifferRowsRedWithIconAndText`
  - `clickingRowHighlightsQuoteInBothTexts`
  - `railLinksToDuplicates`
- [ ] **Step 2:** `npm --prefix frontend test -- compare duplicates`. Expect FAIL.
- [ ] **Step 3:** Implement per §3 (Duplicates UI).
- [ ] **Step 4:** Run the full test suite, `typecheck` and `build`. Expect green.
- [ ] **Step 5:** Commit `feat(ui): duplicate review screen`.

### Task S11: Docs and final verification

**Files:** `README.md` (stretch features, reviews location, shortcuts), `NOTES.md` (stretch results, the S03 approach and its limits, duplicate rules, review storage, what changed in the keyboard model, AI use), `docs/solution/FRONTEND.md` (pointer to §3 here).

- [ ] **Step 1:** Update the docs. Every claim must be checked against the code.
- [ ] **Step 2:** Run `./mvnw -q -o test`, `npm --prefix frontend test`, `npm --prefix frontend run typecheck`, then `./replay.sh` and `git status --porcelain out/ derived/` (expect empty), then `scripts/score.sh` (core still 64/64), then the secret greps. Expect all green.
- [ ] **Step 3:** Commit `docs: stretch items`.

Execution order: S1 → S2 → S4 → S6 → S5 → S3 (live; needs quota) → S7 → S8 → S9 → S10 → S11.
