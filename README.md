# Case Intake

Reads adverse-drug-reaction reports (`documents/*.txt` and `documents/stretch/`), asks a language model for the fields in `catalog/field_catalog.json`, and writes one `out/<ID>.json` per document. Every extracted value carries a quote and its code-point offsets in the text. A document that exists only as an image PDF (S03) is first transcribed to text by a vision model, and that transcript is the text it is extracted from. After all documents, a deterministic detector writes `out/duplicates.json`. A Spring Boot API serves the committed outputs and stores review decisions. A React review screen shows each document next to its fields, lets a reviewer accept, reject or edit each field, and compares duplicate documents side by side.

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

The backend serves the committed `out/`, `documents/`, `derived/` and catalog, and makes no model calls. The only files it writes are review decisions, in `reviews/<ID>.json` (git-ignored, created on the first review; see section 3). `start.sh` does not rebuild a jar that already exists, so after changing backend code run `./mvnw -q -DskipTests package` first.

## 3. Using the UI

**Reading a document**
- Pick a document in the left rail. Each entry shows its id, title, number of cases, flags, a count of risky values and a progress ring for its review.
- The document text is in the centre. For S03, which has no text file, this is the transcript in `derived/S03.txt`, and the flag "Transcribed from image — verify against the PDF" is shown.
- The fields are on the right, grouped by catalog section in catalog order. A document with more than one patient has a tab per case. A document with no case shows a panel that explains why, from its flags.
- Click a field to select it and open its inspector. Its quotes are highlighted in the text, and the text scrolls to the first one. Click a quote in the text to select its field.
- Every value has a badge with an icon, a colour and a text label: the confidence level, `unknown` ("Not stated") or `conflict` (with the candidates). The summary bar counts each state and each review state; click a chip to filter, or use "Risky only" (conflict plus the two lower confidence levels).

**Reviewing**
- On the selected row and in the inspector: **Accept this**, **Reject this** and **Reset** (Reset is shown once the field has a review). Accept and reject move the selection to the next unreviewed field.
- To edit, change the value in the inspector's control and press `Enter` or **Save**. The value is checked against the catalog; an invalid value is refused (the server answers 400) and the message is shown under the control.
- Badges: ✓ Accepted; ⊘ Rejected, with the value struck through; ✎ **Manual edit** on a blue background, with the model's value struck through below it. The model's own badge stays beside the review badge.
- Rejecting a field that has a manual edit first asks "Discard manual edit?". Accepting it keeps the edit.
- In the fields-pane header, **Accept all** and **Reject all** act on the unreviewed fields of the current case; fields that already have a review are never changed. Reject all asks for confirmation. While a summary filter or "Risky only" is active, the buttons read **Accept N shown** / **Reject N shown** and act only on the shown unreviewed fields.
- Progress (reviewed fields / all fields, over all cases) is shown as a bar in the top bar and in the fields-pane header, and as a ring in the rail. A document with no case shows "Nothing to review".
- Reviews are saved by the backend in `reviews/<ID>.json`, which is git-ignored. `out/` is never changed, so `./replay.sh` and the output schema are unaffected. Delete `reviews/` to start the review again.

**Keyboard** (no letter shortcuts: typing always means typing)

| Key | Where | Action |
|---|---|---|
| `↓` / `↑` | Focus in the fields pane | Next / previous visible field. In the document text and the rail the arrows scroll as usual. |
| `Enter`, or typing a character | On the selected row | Start editing. In text and number fields the typed character replaces the value; the other controls open (a single choice jumps to the first option starting with that character). |
| `Enter` | In the editor | Save the edit, then move to the next unreviewed field. |
| `Esc` | In the editor / elsewhere | Cancel the edit / clear the selection. |
| `Alt+A` / `Alt+R` | Field selected (not inside an open editor) | Accept / reject this field, then move to the next unreviewed field. |
| `Alt+Shift+A` / `Alt+Shift+R` | Anywhere except an open editor, text field, open list/menu or dialog | Accept all / reject all unreviewed fields of the case (only the shown ones while a filter is active). Reject asks for confirmation. |

Full rules, including when keys are ignored: [frontend/README.md](frontend/README.md#keyboard).

**Duplicates**
- The rail's **Duplicates (n)** link opens `/duplicates`, which lists each group with its documents, confidence and the reason.
- Opening a group (`/duplicates/<n>`) shows three columns: the first document's text, a comparison table of every catalog field, and the second document's text. Each row is marked ✓ Match, ✗ Differ, ◌ One side only or — Neither, with icon, colour and word. Click a row to highlight that field's quotes in both texts.

## 4. Running the extraction pipeline (needs a key)

Put the key in a `.env` file at the repo root, which is git-ignored and read at startup:

```
GEMINI_API_KEY=<your key>
```

or export it in the shell (`export GEMINI_API_KEY=<your key>`). Then:

```bash
./mvnw -q -DskipTests package
java -jar target/case-intake.jar extract [--only=D04,S03] [--force] [--stage=transcribe|cases|extract]
```

Run it from the repo root; all paths resolve from there.

- With no options it processes every document and **reuses** any recorded fixture, so a stopped run resumes where it left off. The run report on stderr prints a resume command (`--only=<the rest>`) when a run stops early.
- `--only=<ids>`: only these documents.
- `--force`: re-record every stage of the selected documents. The new recording is saved as the next `attempt-<n>`, and replay uses the highest attempt.
- `--stage=transcribe|cases|extract`: re-record only one stage: the transcription of an image-only PDF, case finding, or field extraction. The earlier stages must already have fixtures. Re-recording a transcript also re-records that document's case finding and extraction, because they read the transcript.
- Documents are read from `documents/` and `documents/stretch/` (`app.documents`). A document with a `.txt` is read as text. A document with only a `.pdf` (S03) is sent to the transcription model first; the transcript is written to `derived/<ID>.txt`, the output gets the flag `transcribed_from_image`, and every value from it is capped at the second confidence level (`medium`). A blank transcript gives `unsupported_format` with no case.
- After all documents, the duplicate detector reads every output in `out/` and writes `out/duplicates.json` (section 5 and [NOTES.md](NOTES.md)).
- Exit codes: `0` every document was written; `1` one or more documents failed or were not reached (listed on stderr), or duplicate detection failed after the run; `2` configuration error (missing key, blank model id, bad catalog, bad option, invalid `duplicates` settings).
- Each request and response is written to `fixtures/<ID>/<stage>.attempt-<n>.json` (`transcribe`, `cases` or `extract-case-<i>`) with the key removed. The transcription request keeps the PDF as base64. The writer refuses to save a fixture that still contains the key.

**Provider and models.** Google Gemini (`generateContent` REST API; the key goes in the `x-goog-api-key` header, never the URL). Defaults in `src/main/resources/application.yml` (`llm.models`):

| Stage | Default model | Override |
|---|---|---|
| Transcription (image-only PDFs; the PDF is sent inline) | `gemini-3.6-flash` | `LLM_MODEL_TRANSCRIBE` |
| Case finding | `gemini-3.5-flash-lite` | `LLM_MODEL_CASES` |
| Field extraction | `gemini-3.6-flash` | `LLM_MODEL_EXTRACT` |

- The newest stable Flash, `gemini-3.8-flash`, returned HTTP 503 ("high demand") and timeouts, and those failed attempts used up its free daily quota. `gemini-3.7-flash` is billed to the same 3.8 quota. `gemini-3.6-flash` has its own quota, so the extractions of D01-D08, S01 and S02 were recorded with it.
- The 3.6 quota then ran out during the stretch run. The extractions of **S03 and S04 were recorded with `gemini-3-flash-preview`** (`LLM_MODEL_EXTRACT=gemini-3-flash-preview`); the defaults were not changed. Each output's `model` field names the extraction model that produced it (for D06, which has no case, the case-finding model). The per-document table is in [NOTES.md](NOTES.md).
- To switch provider, set `llm.provider` and add an implementation of `com.caseintake.llm.LlmClient` that is active for that value, as `GeminiLlmClient` is for `gemini`. Prompts, the catalog-generated response schema, validation and fixtures stay the same.

**Free-tier quota.** The client allows 8 requests per minute per model (`llm.rate-limit`), retries 429/500/503 and timeouts at most 3 times with backoff (`llm.retry`), and stops at once when a 429 says the daily quota is gone. Failed attempts count against the daily quota too. A full run of the eight core documents needs 8 case-finding calls and 8 extraction calls, 16 in total (D04 has two cases, D06 has none). The four stretch documents add 9: one transcription (S03), four case-finding and four extraction calls. The transcription and extraction calls share the `gemini-3.6-flash` daily quota.

## 5. Replay, tests and scoring

| Command | What it does |
|---|---|
| `./replay.sh` | Rebuilds all 12 outputs (`out/D01-D08`, `out/S01-S04`), `out/duplicates.json` and the transcript `derived/S03.txt` from `fixtures/`, the documents and the committed catalog. No key, no network. Builds the jar first if it is missing or older than `src/` or `pom.xml`. |
| `./replay.sh <catalog>` | The same recordings, validated against another catalog. A value that no longer passes, or a field the recordings do not cover, comes out `unknown` with a note saying why. |
| `REPLAY_OUT=<dir> ./replay.sh` | Writes the outputs (and `duplicates.json`) to `<dir>` instead of `out/`. |
| `REPLAY_OUT=<dir> REPLAY_DERIVED=<dir2> ./replay.sh` | Also writes the transcripts to `<dir2>` instead of `derived/`, so the committed files are left alone. |
| `./mvnw test` | Backend tests, offline: unit tests, a golden replay test (replay equals the committed `out/`, `out/duplicates.json` and `derived/`), schema conformance of every output, duplicate detection, the review store and endpoints, catalog-change replay tests, and a no-secrets scan. |
| `npm --prefix frontend test` | UI tests (Vitest, Testing Library, MSW), offline, no backend. Run `npm --prefix frontend install` first if `start.sh` has not. |
| `npm --prefix frontend run typecheck` | `tsc --noEmit`, strict. |
| `scripts/score.sh` | Runs `tools/check.py` on `out/` against `answers/` (D01, D02, D03, D05). The stretch documents have no answers and are not scored. |

After `./replay.sh`, `git status --porcelain out/ derived/` should print nothing.

## 6. Troubleshooting

- **Port 8080 in use:** the backend exits and `start.sh` prints the end of `target/backend.log`. Free the port (`ss -ltnp | grep 8080` or `lsof -i :8080`). The UI proxy expects the backend on 8080.
- **Port 5173 in use:** Vite moves to the next free port and prints the URL it uses.
- **A compile error such as `release version 21 not supported`:** `javac` is older than 21. Set `JAVA_HOME` to a JDK 21 (a JRE has no `javac`) and run again.
- **Backend failed to start:** read `target/backend.log`. A stale jar can be rebuilt with `./mvnw -q -DskipTests package`.
- **`extract` exits 2 with "GEMINI_API_KEY is not set":** create `.env` at the repo root or export the variable, and run from the repo root.
- **Daily quota exhausted** (`DailyQuotaExhaustedException`): the run stops and prints which documents remain. Wait for the reset (midnight Pacific time) or use another model, then resume with `java -jar target/case-intake.jar extract --only=<remaining ids>`. Recorded stages are reused. To use another model for one stage without editing `application.yml`, set its override, for example `LLM_MODEL_EXTRACT=<model id>`.
- **Clearing reviews:** stop the backend and delete `reviews/` (or one `reviews/<ID>.json`). A missing file means no reviews; an unreadable one is an error (500), never read as empty.

## 7. Project layout

```
README.md  NOTES.md  start.sh  replay.sh  pom.xml  mvnw
src/main/java/com/caseintake/   backend packages: catalog, validation, text, llm (+ llm.gemini),
                                prompt, fixtures, extraction, output, pipeline, duplicates,
                                review, api, config
src/main/resources/             application.yml, prompts/ (transcription, case-finding, extraction)
src/test/java/                  offline backend tests
frontend/                       review UI (see frontend/README.md)
fixtures/                       recorded model exchanges, one folder per document (D01-D08, S01-S04)
out/                            committed outputs for D01-D08 and S01-S04, and duplicates.json
derived/                        committed transcript of the image-only S03 (rebuilt by replay)
reviews/                        review decisions, written by the backend; git-ignored
scripts/score.sh                runs the checker
docs/solution/                  design documents
documents/ catalog/ schema/ answers/ tools/ GLOSSARY.md ASSIGNMENT.md   provided material, unchanged
```

Documentation:
- [NOTES.md](NOTES.md): checker results, notes on each unanswered document, decisions, the stretch items, limitations, AI use.
- [docs/solution/HLD.md](docs/solution/HLD.md): architecture and the key decisions D1-D12.
- [docs/solution/LLD.md](docs/solution/LLD.md): backend detail (CLI, prompts, fixtures, confidence rules, API, reviews, duplicates).
- [docs/solution/FRONTEND.md](docs/solution/FRONTEND.md): UI decisions and architecture.
- [docs/solution/UI-DESIGN-PROMPT.md](docs/solution/UI-DESIGN-PROMPT.md): the prompt used to design the UI.
- [docs/solution/PLAN.md](docs/solution/PLAN.md): the backend task plan.
- [docs/solution/STRETCH.md](docs/solution/STRETCH.md): design and plan of the stretch items (transcription, duplicates, reviews, keyboard).
- [frontend/README.md](frontend/README.md): UI scripts, architecture and keyboard shortcuts.
