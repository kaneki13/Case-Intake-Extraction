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

There are no letter shortcuts: typing always means typing.

| Key | Where | Action |
|---|---|---|
| `↓` / `↑` | Focus in the fields pane | Select the next / previous field. Only visible fields count (catalog order, after the summary filters, in the current case). With nothing selected, `↓` picks the first field and `↑` the last. No wrap-around. Elsewhere (document text, document list, nothing focused) the arrows scroll as usual. |
| `Enter` | On the selected row | Start editing: focus moves into the field's control. |
| A character | On the selected row | Start editing with that character. Text and number fields: it replaces the value (caret after it). Date: a digit is typed into the date input; anything else just opens it. Single choice: the option list opens with the first option starting with that character highlighted. Multiple choice: the option list opens with the character in its filter box. List: the character goes into the add box. |
| `Enter` | In the editor | Save the edit (`edit` review), then move to the next unreviewed visible field. Unchanged: close the editor, focus back on the row, nothing sent. On a closed option list or multiple choice with nothing changed, Enter opens it. In a list's add box with text, Enter adds the item; on an empty add box it saves. |
| `Esc` | In the editor | Cancel: the shown value comes back, the editor closes, focus returns to the row. Nothing is recorded. |
| `Esc` | Anywhere else | Clear the selection. Focus stays on the row. |
| `Alt+A` / `Alt+R` | Field selected | Accept / reject this field, then move to the next unreviewed visible field. |
| `Alt+Shift+A` | Anywhere | Accept all unreviewed fields of the case. |
| `Alt+Shift+R` | Anywhere | Reject all unreviewed fields of the case: asks for confirmation first. |

The buttons do the same: Accept this, Reject this and Reset on the selected row and in the inspector; Accept all and Reject all in the fields-pane header. One review is saved at a time: while a field or bulk review is saving, both sets of buttons and their keys wait. A field selected from the keyboard is scrolled into view and focused, and the document scrolls to its quote. Clicking a quote in the text selects its field and scrolls its row into view.

Shortcuts are ignored in these cases, so typing and the menus keep working:

- focus is in the editor (only its `Enter` and `Esc` apply), a text field, a select or an editable area;
- an open list, option, menu or dialog has focus;
- Ctrl or ⌘ is held;
- the key was already handled.

`Tab` moves through the controls as usual. Inside an open option list, the arrow keys move, `Space` or `Enter` choose, and `Esc` closes the list and returns focus to its button. `Space` (or a click) opens a multiple-choice list.
