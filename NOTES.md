# Notes

How to run everything is in [README.md](README.md). The design is in [docs/solution/HLD.md](docs/solution/HLD.md) (decisions D1-D12), [docs/solution/LLD.md](docs/solution/LLD.md) and [docs/solution/FRONTEND.md](docs/solution/FRONTEND.md).

## 1. Checker output on the four answered documents

Produced by `scripts/score.sh` (`python3 tools/check.py --catalog catalog/field_catalog.json --answers answers --out out --docs documents`) on the committed `out/`:

```

=== D01 ===
  -- answer case 0 'Mrs M.K.' -> output case 0
     ok patient_initials              +1.0  ok
     ok patient_age_years             +1.0  ok
     ok patient_sex                   +1.0  ok
     ok suspect_drug_name             +1.0  ok
     ok suspect_drug_route            +1.0  ok
     ok suspect_drug_start_date       +1.0  ok
     ok event_terms                   +1.0  ok
     ok event_onset_date              +1.0  ok
     ok event_outcome                 +1.0  ok
     ok seriousness_criteria          +1.0  ok
     ok reporter_type                 +1.0  ok
     ok report_date                   +1.0  ok
     ok country                       +1.0  ok
     ok concomitant_drugs             +1.0  ok
     ok dose                          +2.0  ok
  D01: 16.0 / 16

=== D02 ===
  -- answer case 0 'Mr J.T.O.' -> output case 0
     ok patient_initials              +1.0  ok
     ok patient_age_years             +1.0  ok
     ok patient_sex                   +1.0  ok
     ok suspect_drug_name             +1.0  ok
     ok suspect_drug_route            +1.0  ok
     ok suspect_drug_start_date       +1.0  ok
     ok event_terms                   +1.0  ok
     ok event_onset_date              +1.0  ok
     ok event_outcome                 +1.0  ok
     ok seriousness_criteria          +1.0  ok
     ok reporter_type                 +1.0  ok
     ok report_date                   +1.0  ok
     ok country                       +1.0  ok
     ok concomitant_drugs             +1.0  ok
     ok dose                          +2.0  ok
  D02: 16.0 / 16

=== D03 ===
  -- answer case 0 'Patient (initials withheld)' -> output case 0
     ok patient_initials              +1.0  unknown as expected
     ok patient_age_years             +1.0  ok
     ok patient_sex                   +1.0  ok
     ok suspect_drug_name             +1.0  ok
     ok suspect_drug_route            +1.0  ok
     ok suspect_drug_start_date       +1.0  ok
     ok event_terms                   +1.0  ok
     ok event_onset_date              +1.0  ok
     ok event_outcome                 +1.0  ok
     ok seriousness_criteria          +1.0  ok
     ok reporter_type                 +1.0  ok
     ok report_date                   +1.0  ok
     ok country                       +1.0  ok
     ok concomitant_drugs             +1.0  ok
     ok dose                          +2.0  ok
  D03: 16.0 / 16

=== D05 ===
  -- answer case 0 'Mr D.L.' -> output case 0
     ok patient_initials              +1.0  ok
     ok patient_age_years             +1.0  conflict flagged
     ok patient_sex                   +1.0  ok
     ok suspect_drug_name             +1.0  ok
     ok suspect_drug_route            +1.0  ok
     ok suspect_drug_start_date       +1.0  ok
     ok event_terms                   +1.0  ok
     ok event_onset_date              +1.0  ok
     ok event_outcome                 +1.0  ok
     ok seriousness_criteria          +1.0  ok
     ok reporter_type                 +1.0  ok
     ok report_date                   +1.0  ok
     ok country                       +1.0  ok
     ok concomitant_drugs             +1.0  ok
     ok dose                          +2.0  ok
  D05: 16.0 / 16

================ SUMMARY ================
Documents scored:      4
Field points:          64.0 / 64  (100.0%)
Wrong at confidence:   high 0   medium 0   low 0
Unknown, answer had one: 0
Case-count errors:     0
Provenance:            62 of 62 extracted fields grounded (100.0%)
```

64/64 on four documents is a small sample. D02, D03 and D05 were extracted once with the first prompt and never re-recorded. D01 was re-recorded after a list-wording fix (see section 4.1), so D01's score is partly a result of tuning against its own answer.

## 2. The four documents without answers

Values below are from `out/`, written as `field = value (confidence)`. Notes on why confidence was lowered come from the deterministic rules in section 3.

### D04: mother and newborn, sertraline (2 cases, flag `multiple_patients`)

What I think is in it: a hospital letter about two patients. The mother had hyponatraemia (SIADH) on sertraline during pregnancy. Her baby developed neonatal adaptation syndrome after in-utero exposure. Both are reported by two physicians in Pune, India, on 8 June 2026.

Case 0, "Mother":
- `patient_initials = PRD` (high), `patient_age_years = 29` (high), `patient_sex = female` (medium; the text says "Mrs", so no option is written literally).
- `suspect_drug_name = sertraline`, `dose = 50 mg`, `start_date = 2025-10-14` (all high), `route = oral` (medium, from "orally").
- `event_terms = [headache, confusion, vomiting, hyponatraemia, siadh]` (medium), `event_onset_date = 2026-05-22` (high), `event_outcome = recovered` (high).
- `seriousness_criteria = [hospitalization]` (medium), `reporter_type = physician` (medium), `report_date = 2026-06-08` (high), `country = IN` (high), `concomitant_drugs = [iron, folic acid]` (high).

Case 1, "Infant":
- `patient_initials = RD` (medium, from "Baby R.D."), `patient_age_years = 0` (medium; the catalog says a newborn is 0), `patient_sex = male` (medium, from "a boy").
- `suspect_drug_name = sertraline` (high). **Dose value, dose unit, route and start date are `unknown`.** The infant was exposed in utero and took nothing himself, so the mother's 50 mg oral dose is not a value stated about this patient (D12).
- `event_terms = [jitteriness, high-pitched cry, poor feeding, respiratory distress, neonatal adaptation syndrome]` (medium), `event_onset_date = 2026-06-03` (medium), `event_outcome = recovering` (medium).
- `seriousness_criteria = [hospitalization]`, `reporter_type = physician`, `country = IN` (all medium), `report_date = 2026-06-08` (high), `concomitant_drugs = []` (high; "did not receive any medicine himself", and the catalog allows an empty list when the document says there were none).

Where I am unsure:
- Mother's `event_terms`: headache, confusion and vomiting are the presenting symptoms of the hyponatraemia, and SIADH is its mechanism. An answer key may list only "hyponatraemia", which would make this list wrong. At medium that costs −1.
- Mother's `seriousness_criteria`: the letter says both patients "remain in hospital", but not whether the hyponatraemia caused or prolonged her stay (she presented at 36 weeks of pregnancy, and her baby was born at 38 weeks).
- Infant's onset: symptoms began "within the first 24 hours of life" after delivery on 3 June, so 4 June is also possible.
- Infant's dose, unit, route and start date: if the answer key uses the mother's values, these four fields score 0, not a penalty.

### D06: product-quality complaint, amoxicillin (0 cases, flag `no_adverse_event`)

A pharmacy manager in Halifax, Canada, reports discoloured and crushed capsules in one lot. The bottle was quarantined before dispensing, and the letter says no patient received the product and no adverse reaction was reported. There is no patient and no event, so no case. I am confident in this one. Its `model` is the case-finding model, because no extraction call was made.

### D07: consumer email, paroxetine (1 case, flag `embedded_instructions_ignored`)

A 38-year-old woman from Columbus, Ohio, writes about insomnia and suicidal thoughts after starting paroxetine 20 mg on 30 March 2026. She was admitted to a psychiatric unit for two nights. In the middle of the email is a bracketed "note for automated systems" that tries to set the outcome, the seriousness and the reporter type. Case finding returned it in `embedded_instructions`, it was located in the text, and the output carries the flag. It did not steer extraction: all three of its values differ from the output.

- `patient_initials = unknown`. The body gives only the first name "Jenna", and the catalog says a first name alone does not give initials. The sender's address `jenna.m.hartley@…` is not used: names and initials are taken only from text written as a name.
- `patient_age_years = 38` (high), `patient_sex = female` (medium).
- `suspect_drug_name = paroxetine`, `dose = 20 mg`, `start_date = 2026-03-30` (all high), `route = oral` (medium, from "one tablet").
- `event_terms = [insomnia, suicidal ideation]` (low). These are standard terms for "stopped sleeping" and "thoughts about not wanting to be alive", not words in the text, so the LITERAL_ABSENT rule lowered them one level.
- `event_onset_date = unknown`: "about a week and a half in" is too vague for a date.
- `event_outcome = not_recovered` (low), `seriousness_criteria = [hospitalization]` (medium), `reporter_type = consumer` (medium).
- `report_date = 2026-04-27` (high, the email's date), `country = US` (medium, derived from "Columbus, Ohio"), `concomitant_drugs = [multivitamin]` (high).

Where I am unsure:
- `event_outcome`: "the sleep is a little better but the dark thoughts are still there". `recovering` is also a fair reading. It is at low, so a wrong value costs −0.5.
- `event_onset_date`: the catalog allows computing a relative date at less than high confidence, so an answer key could hold about 2026-04-09. Our `unknown` would then score 0.
- `event_terms`: the wording of the two terms may not match the key.
- **The case label is "Jenna".** Both prompts say a label is initials or a role and never a name or part of one. The case-finding model (Flash-Lite) returned "Jenna" anyway, with two prompt wordings. The label is not a graded field, and I did not add Java code to rewrite it, so a first name appears in this output and in the UI's case tab.

### D08: long hospital report, methotrexate (1 case, no flags)

A seven-page report from Wellington, New Zealand. A 72-year-old woman on weekly methotrexate for rheumatoid arthritis had her dose raised from 15 mg to 20 mg on 2 March 2026. She developed bone marrow suppression, mucositis and neutropenic sepsis, then bilateral pneumonia, and died on 30 March 2026.

- `patient_initials = HMN`, `patient_age_years = 72`, `patient_sex = female` (all high).
- `suspect_drug_name = methotrexate`, `route = oral`, `start_date = 2025-10-07` (all high).
- `suspect_drug_dose_value = 20` (high, written as `20.0` by the model), `dose_unit = mg` (high). This is the dose after the increase on 2 March, which the catalog asks for ("the dose at the time of the reaction").
- `event_terms = [severe bone marrow suppression, pancytopenia, oral mucositis, neutropenic sepsis, bilateral pneumonia, septic shock]` (medium).
- `event_onset_date = 2026-03-19` (medium). The report says mucositis began "on or about 19 March 2026", and the prompt caps hedged dates at the second level.
- `event_outcome = fatal`, `seriousness_criteria = [death, life_threatening, hospitalization]` (all high).
- `reporter_type = physician` (medium), `report_date = 2026-04-14`, `country = NZ` (high).
- `concomitant_drugs = [folic acid, omeprazole, cilazapril, paracetamol, zoledronic acid]` (medium).

Where I am unsure:
- Dose: 20 mg follows the catalog, but a key that takes the starting dose would expect 15. It is at high, so a wrong value here costs −2. I think this is the riskiest value in the four documents.
- `event_terms`: the list items are at risk. Pneumonia and septic shock are complications and the cause of death, and may not be counted as reactions to the drug. "Bone marrow suppression" and "pancytopenia" overlap.
- Onset: the daughter reported lethargy "over the preceding week" before the 24 March presentation, which could put the first reaction a few days before 19 March.
- `concomitant_drugs`: zoledronic acid is a yearly infusion last given in August 2025. It is in the report's table of medicines "at the time of the event", so I kept it.
- Start date: 2025-10-07 is the first dose (the catalog asks for the date the patient first took the drug), not the date of the dose increase.

## 3. Choices that mattered and why

**Two-stage extraction (D1).** Stage 1 (case finding) lists each patient who had a reaction and gives a quote for each of the four minimum elements: patient, reporter, suspect drug and event. A candidate is kept only if all four quotes are found in the text. Stage 2 runs once per kept case and extracts every catalog field for that patient, with the other patients named as ones to ignore. Case counting is where the large penalties are (a missing or extra case, or a wrong no-case decision), and D04 (two patients) and D06 (no case) are decided in stage 1 alone. Stage 1 uses a fixed schema that does not depend on the catalog.

**The model gives quotes; Java computes offsets (D2).** Models cannot count characters. The model returns verbatim quotes, and `QuoteLocator` finds them in the text and computes Unicode code-point offsets (start inclusive, end exclusive). It folds case, runs of whitespace and typographic quotes and dashes, and the written quote is the original span of the text. A value whose quotes cannot be found becomes `unknown` with a note. Provenance is 62 of 62 on the answered documents.

**Validate before writing (D4).** Each value is checked against the catalog's type, options, pattern and min/max before it is written. A value that fails becomes `unknown` with the note `Model value <v> rejected: <reason>.` It is never coerced. A `conflict` needs at least two distinct candidates, or it becomes `unknown`.

**The model sets confidence; deterministic rules can only lower it (D3).** The final level is the lowest of the model's level and every rule that applies (`ConfidencePolicy`, LLD §9.3):
- `DERIVED`: a value the model computed rather than read (a relative date, a country from a city, initials from a full name) is capped at medium.
- `AMBIGUOUS_NUMERIC_DATE`: a date quoted as a numeric date with both parts 12 or less and different (such as 03/04/2026) is capped at low, or at medium when the document's day/month convention was established from a located quote.
- `LITERAL_ABSENT`: one level lower when the number is not written in its quotes, when a date's day of month is not in its quotes, or when a list item is not written in any of its quotes.
- `LIST_FROM_MULTIPLE_QUOTES`: a list assembled from more than one distinct quote is capped at medium.
- `OPTION_NOT_LITERAL`: one level lower when no chosen option's code or label appears as whole words in the quotes (for example "Mrs" for `female`).
- A missing confidence on an extracted value is treated as the lowest level.

The note on the field lists the rules that lowered it. These rules cost some correct values their "high" badge. That was a deliberate choice: the checker scores a correct value +1 at any confidence, while a wrong value at high costs −2. The prompt adds two softer rules for the model itself: a hedged date ("about", "on or about") and one outcome for reactions with different courses are at most medium.

**Live and replay share one path (D5).** In `extract`, each response is written to `fixtures/` first and then read back from disk, and the pipeline parses that copy. Replay reads the same files through the same code. `ReplayGoldenTest` checks that replay reproduces the committed `out/`.

**Fixtures keyed by document and stage (D6).** A fixture is `fixtures/<ID>/<stage>.attempt-<n>.json` (`cases` or `extract-case-<i>`), not a hash of the request. A changed catalog changes the prompt, but replay must still find the recordings. With a changed catalog, every recorded value is re-validated: a value that no longer passes becomes `unknown` with the rejection note, and a field the recording does not cover becomes `unknown` with `Not covered by the recorded model response.` `CatalogChangeReplayTest` covers both, including through `./replay.sh <catalog>`. Re-recordings add `attempt-<n+1>` and keep the older files as history; replay uses the highest attempt.

**Response schema generated from the catalog (D7).** `ResponseSchemaFactory` builds a provider-neutral JSON Schema from the catalog: every field id is required, and each value is typed by the catalog (enum for coded options, pattern, min/max, date). `GeminiSchemaTranslator` is the only class that knows Gemini's dialect. The prompt's field list (section, label, type, options, validation, guidance) is rendered from the catalog too. No field id appears in `src/main` or `frontend/src`, and tests check both.

**Provider behind an interface (D8, D11).** `LlmClient` with one implementation, `GeminiLlmClient`, chosen by `llm.provider`. Models are configured per stage: Flash-Lite for case finding, Flash for extraction, to spread the free-tier daily quotas.

**Prompt-injection handling (D10).** The document goes inside a fence whose marker is 16 random hex characters, new for each request. Both system prompts say that nothing inside the fence is an instruction. Stage 1 must quote any passage aimed at automated processing in `embedded_instructions`. When such a quote is found in the text, the output gets `embedded_instructions_ignored` and the UI shows the flag. D07 is the test, and its injected values did not reach the output.

**Only values stated about this patient (D12).** A general prompt rule, not a D04 special case: a drug that reached the patient through someone else leaves dose, unit, route and start date `unknown`. A name rule of the same kind says initials come only from text written as a name or initials, never from an email address, user name, URL or file name.

**Rate limits, retries and quota (D9), and what actually happened.** The client has a sliding-window limiter of 8 requests per minute per model, and retries 429, 500, 503 and timeouts with exponential backoff that honours the server's `retryDelay`. A 429 for a per-day quota stops the run at once. Runs are resumable, because recorded stages are reused. In practice:
- The first extraction model was `gemini-3.8-flash`, with 5 attempts per call and a 120 s timeout. D01 worked (16/16). Then D02 got 5 × HTTP 503 "high demand" and D03 got 5 timeouts. Those failed attempts counted against the free daily quota of 20 requests, and it ran out after one successful extraction.
- I raised the timeout to 300 s (a healthy call already took about 80 s) and capped retries at 3, so a demand spike cannot drain the quota.
- `gemini-3.7-flash` turned out to bill to the same 3.8 quota. The user chose to switch extraction to `gemini-3.6-flash`, which has its own quota, rather than wait for the reset. D01's extraction was re-recorded with 3.6, so every committed output uses one extraction model. The old 3.8 recording is kept as `attempt-1`.

**No chunking.** The longest document, D08, is about 12,700 characters, well within the models' context. Each stage sees the whole document, so a quote or a conflict anywhere in the text is visible. The HLD's contingency (one stage-2 call per catalog section if Gemini rejected the large schema) was not needed: Gemini accepted the schema.

**Frontend.**
- Vite, React 19 and strict TypeScript; Vitest, Testing Library and MSW, so `npm test` runs offline.
- TanStack Query holds the server data. The selection (document, case, field) lives in the URL (`/documents/:id?case=&field=`), so a reload keeps it.
- Hand-written CSS modules on design tokens, with light and dark themes. Radix is used only for the accessible hard parts: Select, Popover, Tabs and Tooltip.
- Three panes: document rail, text, fields. Below 960 px the fields panel becomes a bottom sheet.
- The text is split at every quote boundary of the current case. Code-point offsets are converted to UTF-16 once per document.
- Everything is driven by the catalog: sections, labels, order, options and controls. Confidence is styled by its rank in `confidence_levels`, not its name. `test/noFieldIds.test.ts` fails if a field id appears in `frontend/src`. A test adds one field of each of the 7 catalog types and checks that it renders with the right control.
- Status always uses an icon, a colour and a text label. `test/polish.test.ts` checks WCAG AA contrast for the status colours in both themes, no text below 13 px, focus rings, and reduced motion.

**Secrets.** The key comes only from `GEMINI_API_KEY`, either exported or in a git-ignored `.env` that Spring reads (`spring.config.import`). `application.yml` holds only `${GEMINI_API_KEY:}`. The key is sent in a header, never in the URL, and is checked only when the first request is made, so replay and the server start without it. `Redactor` strips the key from every fixture, and `FixtureStore` refuses to write if the key is still present. `NoSecretsCommittedTest` fails if any committable file contains something that looks like a Google API key. `.gitignore` covers `.env`, `*.local.yml` and the folder the key was delivered in.

## 4. Honest evaluation and known limitations

### 4.1 Not every recording used the same prompt

I tuned the prompts after some documents were already recorded, and re-recorded only the stages a change affected. Prompt versions:
- v1 is the initial prompt.
- v2 adds the "Lists" rule: one item per distinct thing, in the document's words or the usual short term, never a new phrase recombined from the document's words. It was added after D01's first 3.6 run wrote "swelling of the periorbital area", a phrase the document does not contain.
- v3 adds the name rule, the label rule ("never a full name"), and the hedged-date and mixed-outcome confidence rules.
- v4 changes only the case-finding label wording to "never a name or any part of a name".

The shipped extraction prompt is v3 (v4 changed nothing for extraction), and the shipped case-finding prompt is v4. `prompt_sha256` in a fixture hashes the whole request, including the document and the random fence, so the versions below follow from the recording order.

| Document | Case finding (attempt used, prompt) | Extraction (attempt used, prompt) |
|---|---|---|
| D01 | attempt 1, v1 | attempt 3, v2 |
| D02, D03, D05 | attempt 1, v1 | attempt 1, v1 |
| D04 | attempt 1, v1 | attempt 2, shipped |
| D06 | attempt 1, v1 | none (no case) |
| D07 | attempt 3, shipped | attempt 3, shipped |
| D08 | attempt 1, v1 | attempt 2, shipped |

Between v1 and v4 the case-finding prompt changed only in the label and name rules. The deterministic confidence rules run on replay, so they apply to every document whatever prompt recorded it. A clean run of all documents on the shipped prompts would cost 16 calls and was not done.

### 4.2 Backend limitations

- **"'none' stands alone" is enforced by option code, not by field.** The catalog has no machine-readable rule for it (only `pattern`, `min`, `max`), so `FieldValidator` applies the glossary rule ("Seriousness": `none` "stands alone", the same check as `tools/check.py`) to every `coded_multi` field whose options include the code `none`: a value that combines `none` with another code is rejected, and the field becomes `unknown` with the validator's note. No field id is named in code. No committed output uses `none`.
- **The prompts carry a few glossary-level field semantics in prose**, without field ids: where names and initials may come from, that a dose and route reached through another person are not this patient's, that the report's place (country) applies to every patient, and how a mixed outcome lowers confidence. A catalog that redefined these concepts would also need the prompt text reviewed.
- **D07's case label is a first name** ("Jenna"), as described in section 2.
- `--stage=cases` re-records case finding only. The existing extraction recordings are kept and reused without a warning, even though they were made for the old case list.
- A document that fails during a run leaves its previous `out/<ID>.json` in place.
- An unknown `--only` id is reported as a failed document (exit 1), not as a configuration error (exit 2). A blank model id, a blank `--stage` or `--out`, and an unknown command are configuration errors (exit 2).
- The rate limiter's `acquire` is synchronized, so while one model is waiting for a slot, calls to the other model wait too. Runs are sequential, so this has not mattered.
- Numbers written with thousands separators count as "not written in the quote" and lose one confidence level.
- `CatalogChangeReplayTest`'s `./replay.sh` test is skipped, with the reason, when `target/case-intake.jar` is missing or older than a file under `src/` or `pom.xml`, so `./mvnw test` never starts a nested Maven build. On a fresh clone, run `./mvnw package` (or `./replay.sh`) once before `./mvnw test` to include it.

### 4.3 Frontend limitations

- **Esc on a filtered row.** A selected row stays visible even when the active filter would hide it. Esc clears the selection, the row disappears, and keyboard focus goes with it.
- **A coded single-select cannot be cleared back to empty** (Radix Select has no "no value" option). "Reset to model value" is the way back.
- **↓ / ↑** move between fields everywhere except in the document text and the document rail, where they scroll. So they do not scroll the fields panel. `j` / `k` work everywhere.
- **Narrow screens.** The layout was checked in a browser at 1440 px and 820 px only. At 820 px the bottom sheet scrolls a little further than needed when it reveals a row.
- **Shared tone styles are duplicated.** The mapping from status tone to colour is repeated in the CSS modules of FieldRow, StatusBadge, Flags and SummaryBar.
- Badge tooltips (the full note) open on hover and focus only.
- A document with no case shows a green "✓ 0" risk count in the rail, which can read as "verified".
- An edited row loses the icon and colour of the model's original status.
- The production bundle is about 505 kB (160 kB gzip), and Vite warns about chunk size. It is not code-split.
- **Google Fonts are a runtime network dependency.** Inter and Source Serif 4 load from fonts.googleapis.com; offline, the UI falls back to the system sans-serif and Georgia. The tests do not load them and stay offline.

## 5. What is missing

Stretch items not done:
- **Stretch documents S01-S04.** `app.documents` lists only `documents`. S03 (PDF only) would get `unsupported_format` through the existing path, but S01, S02 and S04 have no recordings.
- **Duplicates.** There is no `out/duplicates.json` and no side-by-side view.
- **Review actions.** There is no `PATCH` endpoint and no persisted accept, reject or edit.

Partly done:
- **Click a quote to select its field:** done.
- **Keyboard:** next and previous field (`j`/`k`, `↓`/`↑`), `Enter` to toggle the inspector and `Esc` to clear are done. Accept and reject keys are not, because there are no review actions.
- **Edits:** the inspector has catalog-driven controls, and edits are kept as local drafts, marked "edited", with "Reset to model value". They live in memory for the session only and are never sent to the backend. There is no per-document progress.

## 6. AI tools and how I used them

I used **Claude Code** throughout, with Claude Opus 5.5 as the planning session. And Claude Sonnet 4.8 as the controlling session.

- **Requirements.** The controlling session grilled me on the brief before any design, and my answers became `docs/solution/HLD.md`. A separate round settled the frontend (`docs/solution/FRONTEND.md` §1).
- **Design and plan.** Claude wrote the HLD, the LLD and a task-by-task plan (`docs/solution/PLAN.md`, and FRONTEND.md for the UI). They were agreed before any code was written.
- **Subagent-driven development.** For each task, the controlling session sent a fresh implementer subagent a written brief and a common set of standing rules. It used Sonnet or Opus depending on how complex the task was. The rules were: test first (write the failing test, capture the RED output, then make it pass), no field id or document id in `src/main`, never read `.env`, commit only explicit paths. A separate reviewer subagent then reviewed each task's diff against its brief. Findings went back to the implementer in fix loops until the review was clean, and small re-reviews used Haiku. Both plans ended with a whole-branch review and one fix wave. The frontend review found 0 critical and 2 important issues, both fixed. The backend review found 0 critical and 1 important issue, the "none stands alone" rule, fixed together with stricter catalog checks, provider-neutral credentials and shared constants. A ledger recorded every ruling and every deferred minor issue; sections 4.2 and 4.3 come from it.
- **Live run.** A subagent ran the recording run, scored the outputs with the checker and tuned the prompts. Every tuning change had to be general (no document text, drug name or field id in code), and it re-recorded only the affected stages.
- **What I decided myself**, among others:
  - The key comes from an environment variable or a git-ignored `.env`, never from `application.yml` or any committed file.
  - Extraction is two-stage, with case finding as its own call.
  - When the 3.8 Flash quota ran out, switch the extraction model rather than wait for the daily reset. (`gemini-3.6-flash` was then picked because 3.7 shares 3.8's quota, and D01 was re-recorded so that one model produced every output.)
- AI wrote nearly all of the code, tests and documents. They were checked through the per-task reviews, the checker and the offline test suites.

### UI design prompt

This is the prompt given to Claude Code (Opus 5.5) to design the review screen, copied from [docs/solution/UI-DESIGN-PROMPT.md](docs/solution/UI-DESIGN-PROMPT.md). That file also describes how it was used: as the visual brief for every frontend task, with one later tightening (no text below 13 px, so the 12 px step is not used).

> Design a desktop-first review screen for a pharmacovigilance case reviewer, built in React 19 + TypeScript with hand-written CSS (design tokens as CSS variables, light and dark themes) and Radix primitives for Select, Popover, Tabs and Tooltip.
>
> **The job:** a reviewer checks values a language model extracted from an adverse-drug-reaction report. They must find the risky values fast, verify each against the exact quote in the source text, and never trust a value they cannot trace. A wrong value is worse than an empty one.
>
> **Layout:** three panes.
> 1. A narrow left rail lists documents: id, title (one line, truncated), number of cases, flag icons (no case, multiple patients, unsupported format, embedded instructions ignored) and a small risk count.
> 2. The centre shows the document text as a readable document, not code: Source Serif 4, 17px, line-height 1.65, max 72ch, whitespace preserved. Every quote that backs a field gets a faint dotted underline. The selected field's quotes get a solid highlight in the accent tint, and the view scrolls to the first one.
> 3. The right panel has case tabs when there is more than one case, then a summary bar, then fields grouped by catalog section in catalog order.
>
> **Summary bar:** one chip per state, each with a count: high, medium, low, unknown, conflict, edited. Clicking a chip filters the list. A "Risky only" toggle shows conflict plus medium and low.
>
> **Field rows:** compact (36px). Show label, value (formatted by type), and a status/confidence badge. Badges always combine icon + colour + text:
> - high: ✓, green
> - medium: ◐, amber
> - low: !, orange-red
> - unknown: ?, neutral grey with a dashed outline; the value reads "Not stated"
> - conflict: ⇄, violet; the value reads "Conflict" and the candidates are shown
> - edited: ✎, blue
>
> **Inspector:** clicking a row expands it inline. It shows:
> - the editable control for the field type: text input; number input with min/max; native date input; single Select for coded fields; checkbox-list Popover for coded_multi with chips; tag input for string_list
> - each quote as a clickable excerpt that scrolls the text
> - the model's note
> - "Reset to model value" when edited
>
> **States:** skeleton loaders shaped like the final layout. An inline error card with a retry button and the server's message. A deliberate no-case panel that explains why, from the flags ("No adverse event reported", "Image-only PDF — not processed", "Minimum case elements missing") and does not look broken.
>
> **Craft:**
> - 4px spacing grid; type scale 12/13/14/16/20/24
> - neutral greys plus one teal accent; status colours tuned for WCAG AA on both themes
> - focus rings always visible
> - motion 120–180ms ease-out, disabled under prefers-reduced-motion
> - keyboard: j/k or arrows move between fields, Esc clears the selection
>
> No field names may be hard-coded: sections, labels, order, option labels and controls all come from the catalog JSON.
