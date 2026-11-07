# Acceptance criteria — case-intake backend (source: docs/solution/LLD.md, PLAN.md)

## AC-1 Catalog-only field definitions
Trigger: a field is added to, or a pattern tightened in, a copy of catalog/field_catalog.json and `./replay.sh <copy>` runs.
Observable: every case in every out file contains the new field as `unknown` with note "Not covered by the recorded model response."; values failing the tightened pattern become `unknown` with note starting "Model value"; `grep` finds no catalog field id in src/main.
Rejects: hard-coded field ids, a crash or missing field on catalog change.

## AC-2 Provenance by code-point offsets
Trigger: any extracted value is written.
Observable: each provenance entry satisfies norm(text[start:end]) == norm(quote) with Python code-point indexing; quotes shorter than 3 chars or not found turn the field `unknown`.
Rejects: model-supplied offsets, UTF-16 offsets, partial spans for ellipsis quotes.

## AC-3 Validation before write, confidence only lowered
Trigger: a model value is outside pattern/min/max/options, or is a derived/ambiguous date.
Observable: invalid → `unknown` with "Model value <v> rejected: …"; derived → confidence ≤ medium; ambiguous dd/mm vs mm/dd without located hint → low.
Rejects: coercion (e.g. "Female"→"female"), confidence raised by rules.

## AC-4 Replay reproduces out/ offline
Trigger: `./replay.sh` with no key and no network.
Observable: exit 0 and out/ JSON-equal to the committed out/; ReplayGoldenTest passes under `./mvnw test`.
Rejects: any HTTP call in replay mode, timestamps or non-determinism in out/.

## AC-5 No secret committed
Trigger: `./mvnw test`.
Observable: NoSecretsCommittedTest finds no `AIza[0-9A-Za-z_-]{35}` in `git ls-files -co --exclude-standard`; fixtures contain "REDACTED" never the key; FixtureStore refuses to write a body that still contains the key.
Rejects: key in application.yml, fixtures, URLs or logs.

## AC-6 Minimum elements and unsupported format
Trigger: a document lacks one of patient/reporter/suspect drug/event, or has no .txt.
Observable: `cases: []` with flag `missing_minimum_elements` / `no_adverse_event`, or `unsupported_format` with zero model calls.
Rejects: a case emitted without all four located element quotes.
