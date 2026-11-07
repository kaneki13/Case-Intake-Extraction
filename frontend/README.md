# Case review UI

The reviewer's screen for the case intake pipeline. It shows each document next to the fields the model extracted, highlights and scrolls to the quote behind every value, and makes risky values easy to spot. The UI talks to the backend only through `/api` (LLD §12). The design and plan are in [docs/solution/FRONTEND.md](../docs/solution/FRONTEND.md), and the visual brief is in [docs/solution/UI-DESIGN-PROMPT.md](../docs/solution/UI-DESIGN-PROMPT.md).

## Scripts

Requires Node 22 and npm 10. Run them from the repo root with `npm --prefix frontend <script>`, or from this folder with `npm run <script>`.

| Script | What it does |
|---|---|
| `dev` | Vite dev server on http://localhost:5173. `/api` is proxied to the backend on :8080. To run both, use `./start.sh` at the repo root. |
| `test` | Vitest, Testing Library and MSW. Runs offline against the committed fixtures, with no backend. |
| `typecheck` | `tsc --noEmit`, in strict mode. |
| `build` | Type-checks, then writes a production bundle to `dist/`. |

## Architecture

- **Server state** comes from TanStack Query: `['catalog']`, `['documents']` and `['document', id]` (`src/api/`).
- **The selection** lives in the URL: `/documents/:id?case=<i>&field=<fieldId>` (`hooks/useSelection.ts`). A field picked away from its row, by a quote click or the keyboard, carries a one-off "reveal" in the history state. The fields panel uses it to bring that row into view.
- **Panes:**
  - `DocumentRail` lists the documents.
  - `DocumentView` and `SegmentedText` show the text. Every quote is underlined, and the selected field's quotes are highlighted and scrolled to.
  - `FieldsPanel` has the case tabs, the summary bar and its filters, and the rows grouped by catalog section. A selected row opens a `FieldInspector` with its catalog-driven control (`components/controls/`), its quotes and the model's note.
  - Below 960px the fields panel becomes a bottom sheet.
- **Catalog-driven:** labels, sections, order, options and controls come from the catalog JSON. No field id appears in `src/`, and `test/noFieldIds.test.ts` enforces that. Confidence is styled by its rank in `confidence_levels`, not by its name.
- **Pure logic** sits in `src/lib/` and is unit-tested:
  - `offsets`: code point ↔ UTF-16
  - `segments`: splitting the text at quote boundaries
  - `risk`, `status`, `format`, `fieldRows`
  - `drafts`: in-memory edits keyed by document, case and field
- **Styling** is hand-written CSS modules on the tokens in `src/styles/tokens.css`, with light and dark themes. No text is smaller than 13px. Status colours meet WCAG AA on both themes. Focus rings are always visible. Motion is switched off under `prefers-reduced-motion`. `test/polish.test.ts` checks these rules.
- **Edits are local drafts.** They are marked "edited", can be reset to the model's value, and are not saved yet (persistence comes with the PATCH stretch item).

## Keyboard

| Key | Action |
|---|---|
| `j` / `↓` | Select the next field. Only visible fields count (catalog order, after the summary filters, in the current case). With nothing selected, this picks the first field. `↓` does this only outside the document text and the document list; there it scrolls. |
| `k` / `↑` | Select the previous field. With nothing selected, this picks the last field. `↑` does this only outside the document text and the document list; there it scrolls. |
| `Enter` | Toggle the inspector: close the selected field, or reopen the last one. When focus is on a row, Enter clicks that row. |
| `Esc` | Clear the selection. Focus stays on the row. |

The selected row is scrolled into view and focused, and the document scrolls to its quote. Clicking a quote in the text selects its field and scrolls its row into view.

Shortcuts are ignored in these cases, so typing and the menus keep working:

- focus is in a text field, select or editable area;
- an open list, option, menu or dialog has focus;
- Ctrl, ⌘ or Alt is held;
- the key is `↓` or `↑` and focus is in the document text or the document list (they scroll; `j` / `k` still work there);
- the key was already handled.

On a closed option picker, `j` and `k` stay with the picker, which uses letters to jump to an option.

`Tab` moves through the controls as usual. Inside an open option list, the arrow keys move, `Space` or `Enter` choose, and `Esc` closes the list and returns focus to its button.
