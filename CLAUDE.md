# Case Intake (ADR extraction pipeline)

Java 21, Spring Boot 3.5, Maven; React 19 + TypeScript review UI in `frontend/`. Design: `docs/solution/HLD.md`, `docs/solution/LLD.md`, `docs/solution/FRONTEND.md`; stretch items: `docs/solution/STRETCH.md`. Exercise brief: `ASSIGNMENT.md`.

## Commands
- Build and test: `./mvnw test` (run from repo root; all paths resolve from the root). Needs a JDK 21 (with javac) as JAVA_HOME.
- Package: `./mvnw package` produces `target/case-intake.jar`.
- Server (port 8080): `java -jar target/case-intake.jar`. Server and UI together: `./start.sh` (Vite on :5173).
- Extract (records fixtures, needs key): `java -jar target/case-intake.jar extract [--only=D04,S03] [--stage=transcribe|cases|extract] [--force]`. Per-stage model overrides: `LLM_MODEL_TRANSCRIBE`, `LLM_MODEL_CASES`, `LLM_MODEL_EXTRACT`.
- Replay (offline, from fixtures): `./replay.sh [catalog]`, or `java -jar target/case-intake.jar replay [--only=...] [--out=out] [--derived=derived]`. `REPLAY_OUT=<dir>` and `REPLAY_DERIVED=<dir>` redirect `replay.sh`. After `./replay.sh`, `git status --porcelain out/ derived/` must print nothing.
- Exit codes: 0 all written; 1 a document failed (in extract, also a duplicate-detection failure after the run); 2 configuration error.
- Score: `scripts/score.sh` (core 64/64 on D01, D02, D03, D05).
- Frontend: `npm --prefix frontend test`, `npm --prefix frontend run typecheck`, `npm --prefix frontend run build`.
- Key: put `GEMINI_API_KEY=...` in a git-ignored `.env` at the root, or export it.

## Package map (base `com.caseintake`, LLD section 2)
- `catalog`: load and self-check the catalog; the only source of field metadata.
- `validation`: check one value against one field definition.
- `text`: code-point view of a document; quote location.
- `llm`, `llm.gemini`: provider-neutral model access (text plus inline attachments), Gemini REST adapter, `LlmProperties`.
- `prompt`: prompts and JSON Schemas (case finding, extraction generated from the catalog, transcription).
- `fixtures`: record and replay model exchanges.
- `extraction`: model JSON to validated cases, confidence policy (incl. `TRANSCRIBED_SOURCE`).
- `output`: output model and serialisation.
- `pipeline`: orchestration and CLI commands (`ExtractCommand`, `ReplayCommand`); `TranscriptStore` writes `derived/<ID>.txt` for image-only PDFs.
- `duplicates`: deterministic duplicate detection after all documents; writes `out/duplicates.json` (LLD §15, thresholds in yml `duplicates`).
- `review`: review store (`reviews/<ID>.json`), `ReviewService`, progress (LLD §12.1).
- `api`: REST layer (catalog, documents, duplicates, review PATCH endpoints).
- `config`: `AppProperties`.

## Data folders
- `documents/`, `documents/stretch/`: provided input. `fixtures/`: recorded exchanges. `out/`: committed outputs and `duplicates.json`.
- `derived/`: committed transcript of S03, rebuilt by replay. `reviews/`: reviewer decisions, git-ignored; reviews never go into `out/`.

## Conventions
- No catalog field ids or document-specific text in `src/main` or `frontend/src`; field metadata comes from the catalog, duplicate section ids from configuration.
- Test-first: write the failing test, see it fail, then implement. All tests are offline.
- No silent fallbacks: invalid input is a 400/404 problem+json; pipeline failures follow the exit codes.
- Never put an API key in any file, prompt or log. `Gemini API Key/`, `.env` and `*.local.yml` are git-ignored; `NoSecretsCommittedTest` guards this.
- Never use `git add -A`; add explicit paths.
