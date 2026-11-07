import { useEffect, useRef, type RefObject } from 'react'

export type ReviewKey = 'accept' | 'reject'

export interface FieldKeyboardOptions {
  /** The visible field ids of the current case, in display order (catalog order after filters). */
  order: readonly string[]
  /** The selected field, or null. */
  selected: string | null
  /** The selected row's button, when there is one. */
  selectedRow: RefObject<HTMLElement | null>
  /** Select a field from the keyboard. */
  select: (fieldId: string) => void
  /** Clear the selection from the keyboard. */
  clear: () => void
  /**
   * Start editing the selected field: from Enter (`seed` null) or from a typed character.
   * Returns false when the key must go on to the control that now has focus (the browser types it there).
   */
  startEdit: (seed: string | null) => boolean
  /** Accept or reject the selected field. */
  review: (action: ReviewKey) => void
  /** Accept or reject every unreviewed field of the case (reject asks first). */
  reviewAll: (action: ReviewKey) => void
}

/** The fields pane (the aside): ↑ / ↓ move between fields only while focus is inside it. */
export const FIELDS_PANE_ID = 'fields'
/** Marks an open field editor: it owns its keys (Enter saves, Esc cancels; see FieldInspector). */
export const EDITOR_ATTR = 'data-field-editor'

const FIELDS_PANE = `#${FIELDS_PANE_ID}`
const EDITOR = `[${EDITOR_ATTR}]`
/** Where a key press belongs to something else: text entry, or an open list, menu or dialog. */
const TEXT_ENTRY = 'input, textarea, select, [contenteditable]:not([contenteditable="false"])'
const OPEN_WIDGET = '[role="listbox"], [role="option"], [role="dialog"], [role="menu"], [role="combobox"][aria-expanded="true"], dialog'
/** Elements that use the arrow keys themselves. */
const OWN_ARROWS = '[role="tab"], [role="tablist"], [role="radiogroup"], [role="slider"]'
/** Elements that act on Enter themselves (Enter clicks them). */
const ACTIVATES_ON_ENTER = 'button, a[href], summary, [role="button"], [role="tab"], [role="checkbox"], [role="switch"], [role="menuitem"], [role="link"]'

/** The key's letter. Option+A on a Mac reports `å`, so a non-letter falls back to the physical key. */
function letterOf(event: KeyboardEvent): string {
  if (/^[a-z]$/i.test(event.key)) return event.key.toLowerCase()
  return /^Key[A-Z]$/.test(event.code) ? event.code.slice(3).toLowerCase() : ''
}

/** One typed character. Space is left alone: on a row or button it clicks. */
const isPrintable = (key: string) => [...key].length === 1 && key !== ' '

/**
 * The review keyboard:
 * - ↑ / ↓ select the previous / next visible field, only while focus is inside the fields pane (no
 *   wrap-around; with nothing selected, ↓ starts at the first and ↑ at the last). Elsewhere they scroll.
 * - On the selected row, Enter opens its editor and a typed character starts the edit with that
 *   character. Inside the editor, Enter saves and Esc cancels (FieldInspector handles those).
 * - Esc with no editor open clears the selection.
 * - Alt+A / Alt+R accept / reject the selected field; Alt+Shift+A / Alt+Shift+R accept / reject all.
 * Keys are left alone inside an open editor, text entry, an open list, menu or dialog, with Ctrl or ⌘
 * held, or when something else already handled them. There are no letter shortcuts.
 */
export function useFieldKeyboard(options: FieldKeyboardOptions): void {
  // One listener for the panel's lifetime; it reads the latest options from the ref.
  const latest = useRef(options)
  useEffect(() => {
    latest.current = options
  })

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.ctrlKey || event.metaKey) return
      const target = event.target instanceof Element ? event.target : null
      if (target?.closest(`${OPEN_WIDGET}, ${EDITOR}, ${TEXT_ENTRY}`)) return
      const { order, selected, selectedRow, select, clear, startEdit, review, reviewAll } = latest.current

      if (event.altKey) {
        const letter = letterOf(event)
        const action: ReviewKey | null = letter === 'a' ? 'accept' : letter === 'r' ? 'reject' : null
        if (action === null) return
        event.preventDefault()
        if (event.shiftKey) reviewAll(action)
        else if (selected !== null) review(action)
        return
      }

      const inFields = target?.closest(FIELDS_PANE) != null
      const row = selectedRow.current
      const onSelectedRow = target !== null && target === row
      // Inside a row other than the selected one, that row's own keys apply.
      const item = target?.closest('li') ?? null
      const inOtherRow = item !== null && item !== row?.closest('li')

      switch (event.key) {
        case 'ArrowDown':
        case 'ArrowUp': {
          if (!inFields || target?.closest(OWN_ARROWS)) return
          const down = event.key === 'ArrowDown'
          const at = selected === null ? -1 : order.indexOf(selected)
          const next =
            at < 0
              ? order[down ? 0 : order.length - 1]
              : order[down ? Math.min(at + 1, order.length - 1) : Math.max(at - 1, 0)]
          if (next === undefined) return
          // The arrows would otherwise scroll the pane as well.
          event.preventDefault()
          if (next !== selected) select(next)
          return
        }
        case 'Enter':
          // Keys of an input-method composition are the input method's: they never start an edit.
          if (event.isComposing || !inFields || selected === null || inOtherRow) return
          // The selected row would toggle shut on Enter: here Enter edits. Other buttons keep Enter.
          if (!onSelectedRow && target?.closest(ACTIVATES_ON_ENTER)) return
          if (startEdit(null)) event.preventDefault()
          return
        case 'Escape':
          if (selected === null) return
          event.preventDefault()
          clear()
          return
        default:
          if (event.isComposing || !isPrintable(event.key) || !inFields || selected === null || inOtherRow) return
          if (startEdit(event.key)) event.preventDefault()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])
}
