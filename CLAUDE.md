# Case Intake (ADR extraction pipeline)

Java 21, Spring Boot 3.5, Maven. Design: `docs/solution/HLD.md`, `docs/solution/LLD.md`. Exercise brief: `ASSIGNMENT.md`.

## Commands
- Build and test: `./mvnw test` (run from repo root; all paths resolve from the root). Needs a JDK 21 (with javac) as JAVA_HOME.
- Package: `./mvnw package` produces `target/case-intake.jar`.
- Server (port 8080): `java -jar target/case-intake.jar`
- Extract (records fixtures, needs key): `java -jar target/case-intake.jar extract [--only=D04] [--stage=cases|extract] [--force]`
- Replay (offline, from fixtures): `java -jar target/case-intake.jar replay [--only=...] [--out=out]`
- Key: put `GEMINI_API_KEY=...` in a git-ignored `.env` at the root, or export it.

## Package map (base `com.caseintake`, LLD section 2)
- `catalog`: load and self-check the catalog; the only source of field metadata.
- `validation`: check one value against one field definition.
- `text`: code-point view of a document; quote location.
- `llm`, `llm.gemini`: provider-neutral model access, Gemini REST adapter, `LlmProperties`.
- `prompt`: prompts and JSON Schemas generated from the catalog.
- `fixtures`: record and replay model exchanges.
- `extraction`: model JSON to validated cases, confidence policy.
- `output`: output model and serialisation.
- `pipeline`: orchestration and CLI commands (`ExtractCommand`, `ReplayCommand`).
- `api`: REST layer.
- `config`: `AppProperties`.

## Conventions
- No catalog field ids in `src/main`; field metadata comes from the catalog only.
- Test-first: write the failing test, see it fail, then implement. All tests are offline.
- Never put an API key in any file, prompt or log. `Gemini API Key/`, `.env` and `*.local.yml` are git-ignored; `NoSecretsCommittedTest` guards this.
- Never use `git add -A`; add explicit paths.
