# UI design prompt

This is the exact prompt given to the AI (Claude Code, Opus 5.5) to design the review screen. It is copied verbatim from [FRONTEND.md §2](FRONTEND.md#2-ui-design-prompt-recorded-verbatim-for-notesmd), which stays the source of truth. Revisions are added there as dated "Revision" lines, never by editing the prompt in place.

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

## How it was used

The prompt was written during the frontend grilling round, after the decisions in [FRONTEND.md §1](FRONTEND.md#1-decisions) were agreed, and it was used as the visual brief (FRONTEND.md §2, referenced by every frontend task). Each task built one slice of the screen against it: tokens and the shell first, then the document text, the fields panel, the inspector, and finally keyboard and polish. Where a later rule tightened the prompt, the rule won and is recorded in the code and the task reports. The one such change: no text is set below 13px, so the type scale's 12px step is not used. The status colours were measured against the prompt's "WCAG AA on both themes" requirement; `frontend/test/polish.test.ts` checks them on every test run. The prompt's ban on hard-coded field names is enforced by `frontend/test/noFieldIds.test.ts`.
