# Case Intake

Reads adverse-drug-reaction reports (`documents/*.txt`), asks a language model for the fields in `catalog/field_catalog.json`, and writes one `out/<ID>.json` per document. Every extracted value carries a quote and its code-point offsets in the text. A Spring Boot API serves the committed outputs, and a React review screen shows each document next to its fields.

Backend: Java 21, Spring Boot 3.5, Maven. UI: React 19, TypeScript, Vite. The exercise brief is `ASSIGNMENT.md`. Results, decisions and known gaps are in [NOTES.md](NOTES.md).

## 1. Prerequisites

No API key is needed to run the UI, the replay or the tests.

| Tool | Version | Check | Needed for |
|---|---|---|---|
| JDK (full JDK, with `javac`) | 21 | `javac -version` | backend build and run |
| Node.js | 22 | `node -v` | UI |
| npm | 10 | `npm -v` | UI |
| bash | any recent | `bash --version` | `start.sh`, `replay.sh` |
| curl | any | `curl --version` | `start.sh` waits for the backend with it |
| Python 3 | 3.x | `python3 --version` | `scripts/score.sh` only |

If your default `javac` is older than 21, point `JAVA_HOME` at a JDK 21 before running anything (`export JAVA_HOME=/path/to/jdk-21`). `./mvnw`, `start.sh` and `replay.sh` all use `JAVA_HOME` when it is set.

The first build downloads Maven and npm dependencies, so it needs network once. After that everything above runs offline.

## 2. Quick start (committed fixtures, no key)

1. `git clone https://github.com/kaneki13/Case-Intake-Extraction.git`
2. `cd Case-Intake-Extraction`
3. `./start.sh`
   - If `target/case-intake.jar` is missing, it builds it with `./mvnw -q -DskipTests package`.
   - If `frontend/node_modules` is missing, it runs `npm --prefix frontend install`.
   - It starts the backend on http://localhost:8080 (output in `target/backend.log`) and waits up to 60 s until `GET /api/catalog` answers. If the backend exits or does not answer, it stops and prints the log path.
   - It then starts the Vite dev server on :5173, which proxies `/api` to :8080.
4. Open http://localhost:5173.
5. Stop both servers with Ctrl+C.

The backend only serves the committed `out/`, `documents/` and catalog; it makes no model calls. `start.sh` does not rebuild a jar that already exists, so after changing backend code run `./mvnw -q -DskipTests package` first.

## 3. Using the UI

- Pick a document in the left rail. Each entry shows its id, title, number of cases, flags and a count of risky values.
- The document text is in the centre. The fields are on the right, grouped by catalog section in catalog order. A document with more than one patient has a tab per case. A document with no case shows a panel that explains why, from its flags.
- Click a field to open its inspector. Its quotes are highlighted in the text, and the text scrolls to the first one. Click a quote in the text to select its field.
- Every value has a badge with an icon, a colour and a text label: the confidence level, `unknown` ("Not stated") or `conflict` (with the candidates). The summary bar counts each state; click a chip to filter, or use "Risky only" (conflict plus the two lower confidence levels).
- Edits in the inspector are local drafts, marked "edited", and are not saved.
- Keyboard: `j` / `k` (or `↓` / `↑`) next and previous field, `Enter` toggles the inspector, `Esc` clears the selection. Details in [frontend/README.md](frontend/README.md#keyboard).

## 4. Running the extraction pipeline (needs a key)

Put the key in a `.env` file at the repo root, which is git-ignored and read at startup:

```
GEMINI_API_KEY=<your key>
```

or export it in the shell (`export GEMINI_API_KEY=<your key>`). Then:

```bash
./mvnw -q -DskipTests package
java -jar target/case-intake.jar extract [--only=D04,D07] [--force] [--stage=cases|extract]
```

Run it from the repo root; all paths resolve from there.

- With no options it processes every document and **reuses** any recorded fixture, so a stopped run resumes where it left off. The run report on stderr prints a resume command (`--only=<the rest>`) when a run stops early.
- `--only=<ids>`: only these documents.
- `--force`: re-record every stage of the selected documents. The new recording is saved as the next `attempt-<n>`, and replay uses the highest attempt.
- `--stage=cases|extract`: re-record only stage 1 (case finding) or stage 2 (field extraction). The earlier stage must already have fixtures.
- Exit codes: `0` every document was written; `1` one or more documents failed or were not reached (listed on stderr); `2` configuration error (missing key, bad catalog, bad option).
- Each request and response is written to `fixtures/<ID>/<stage>.attempt-<n>.json` with the key removed. The writer refuses to save a fixture that still contains the key.

**Provider and models.** Google Gemini (`generateContent` REST API; the key goes in the `x-goog-api-key` header, never the URL).
- Case finding: `gemini-3.5-flash-lite`.
- Field extraction: `gemini-3.6-flash`. The newest stable Flash, `gemini-3.8-flash`, returned HTTP 503 ("high demand") and timeouts, and those failed attempts used up its free daily quota. `gemini-3.7-flash` is billed to the same 3.8 quota. `gemini-3.6-flash` has its own quota, so all committed extractions were recorded with it.
- Both are set in `src/main/resources/application.yml` (`llm.models`) and can be overridden with the environment variables `LLM_MODEL_CASES` and `LLM_MODEL_EXTRACT`.
- To switch provider, set `llm.provider` and add an implementation of `com.caseintake.llm.LlmClient` that is active for that value, as `GeminiLlmClient` is for `gemini`. Prompts, the catalog-generated response schema, validation and fixtures stay the same.

**Free-tier quota.** The client allows 8 requests per minute per model (`llm.rate-limit`), retries 429/500/503 and timeouts at most 3 times with backoff (`llm.retry`), and stops at once when a 429 says the daily quota is gone. Failed attempts count against the daily quota too. A full run of the eight core documents needs 8 case-finding calls and 8 extraction calls, 16 in total (D04 has two cases, D06 has none).

## 5. Replay, tests and scoring

| Command | What it does |
|---|---|
| `./replay.sh` | Rebuilds `out/` from `fixtures/`, `documents/` and the committed catalog. No key, no network. Builds the jar first if it is missing or older than `src/` or `pom.xml`. |
| `./replay.sh <catalog>` | The same recordings, validated against another catalog. A value that no longer passes, or a field the recordings do not cover, comes out `unknown` with a note saying why. |
| `REPLAY_OUT=<dir> ./replay.sh` | Writes the outputs to `<dir>` instead of `out/`. |
| `./mvnw test` | Backend tests, offline: unit tests, a golden replay test (replay equals the committed `out/`), schema conformance of every output, catalog-change replay tests, and a no-secrets scan. |
| `npm --prefix frontend test` | UI tests (Vitest, Testing Library, MSW), offline, no backend. Run `npm --prefix frontend install` first if `start.sh` has not. |
| `npm --prefix frontend run typecheck` | `tsc --noEmit`, strict. |
| `scripts/score.sh` | Runs `tools/check.py` on `out/` against `answers/` (D01, D02, D03, D05). |

After `./replay.sh`, `git status --porcelain out/` should print nothing.

## 6. Troubleshooting

- **Port 8080 in use:** the backend exits and `start.sh` prints the end of `target/backend.log`. Free the port (`ss -ltnp | grep 8080` or `lsof -i :8080`). The UI proxy expects the backend on 8080.
- **Port 5173 in use:** Vite moves to the next free port and prints the URL it uses.
- **A compile error such as `release version 21 not supported`:** `javac` is older than 21. Set `JAVA_HOME` to a JDK 21 (a JRE has no `javac`) and run again.
- **Backend failed to start:** read `target/backend.log`. A stale jar can be rebuilt with `./mvnw -q -DskipTests package`.
- **`extract` exits 2 with "GEMINI_API_KEY is not set":** create `.env` at the repo root or export the variable, and run from the repo root.
- **Daily quota exhausted** (`DailyQuotaExhaustedException`): the run stops and prints which documents remain. Wait for the reset (midnight Pacific time) or use another model, then resume with `java -jar target/case-intake.jar extract --only=<remaining ids>`. Recorded stages are reused.

## 7. Project layout

```
README.md  NOTES.md  start.sh  replay.sh  pom.xml  mvnw
src/main/java/com/caseintake/   backend packages: catalog, validation, text, llm (+ llm.gemini),
                                prompt, fixtures, extraction, output, pipeline, api, config
src/main/resources/             application.yml, prompts/case-finding.md, prompts/extraction.md
src/test/java/                  offline backend tests
frontend/                       review UI (see frontend/README.md)
fixtures/                       recorded model exchanges, one folder per document
out/                            committed outputs for D01-D08
scripts/score.sh                runs the checker
docs/solution/                  design documents
documents/ catalog/ schema/ answers/ tools/ GLOSSARY.md ASSIGNMENT.md   provided material, unchanged
```

Documentation:
- [NOTES.md](NOTES.md): checker results, notes on each unanswered document, decisions, limitations, AI use.
- [docs/solution/HLD.md](docs/solution/HLD.md): architecture and the key decisions D1-D12.
- [docs/solution/LLD.md](docs/solution/LLD.md): backend detail (CLI, prompts, fixtures, confidence rules, API).
- [docs/solution/FRONTEND.md](docs/solution/FRONTEND.md): UI decisions and architecture.
- [docs/solution/UI-DESIGN-PROMPT.md](docs/solution/UI-DESIGN-PROMPT.md): the prompt used to design the UI.
- [docs/solution/PLAN.md](docs/solution/PLAN.md): the backend task plan.
- [frontend/README.md](frontend/README.md): UI scripts, architecture and keyboard shortcuts.
