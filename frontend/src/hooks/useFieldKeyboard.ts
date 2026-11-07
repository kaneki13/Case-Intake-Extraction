import { useEffect, useRef } from 'react'

export interface FieldKeyboardOptions {
  /** The visible field ids of the current case, in display order (catalog order after filters). */
  order: readonly string[]
  /** The selected field, or null. */
  selected: string | null
  /** Select a field from the keyboard. */
  select: (fieldId: string) => void
  /** Clear the selection from the keyboard. */
  clear: () => void
}

/** Where a key press belongs to something else: text entry, or an open list, menu or dialog. */
const TEXT_ENTRY = 'input, textarea, select, [contenteditable]:not([contenteditable="false"])'
const OPEN_WIDGET = '[role="listbox"], [role="option"], [role="dialog"], [role="menu"], [role="combobox"][aria-expanded="true"]'
/** Elements that act on Enter themselves (a field row is one: Enter clicks it, which toggles it). */
const ACTIVATES_ON_ENTER = 'button, a[href], summary, [role="button"], [role="tab"], [role="checkbox"], [role="switch"], [role="menuitem"], [role="link"]'
/** Panes that scroll with the arrow keys: there ↓ / ↑ scroll the pane, and only j / k move the selection. */
const SCROLLS_WITH_ARROWS = '#document, nav'

function ownedElsewhere(event: KeyboardEvent): boolean {
  if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return true
  const target = event.target
  if (!(target instanceof Element)) return false
  if (target.closest(TEXT_ENTRY) || target.closest(OPEN_WIDGET)) return true
  if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && target.closest(SCROLLS_WITH_ARROWS)) return true
  // A closed Select trigger still takes printable keys as type-ahead (it would change the value).
  return event.key.length === 1 && target.closest('[role="combobox"]') !== null
}

/**
 * Review shortcuts for the fields panel:
 * - `j` / ↓ selects the next visible field, `k` / ↑ the previous (no wrap-around; with nothing
 *   selected, `j` starts at the first and `k` at the last);
 * - `Enter` toggles the inspector: it closes the selected field, or reopens the last one;
 * - `Esc` clears the selection.
 * Keys are left alone in text entry, inside an open list, menu or dialog, with Ctrl/⌘/Alt held, or
 * when something else already handled them. ↓ / ↑ are also left alone in the document pane and the
 * document rail, so they scroll as usual; `j` / `k` work everywhere.
 */
export function useFieldKeyboard(options: FieldKeyboardOptions): void {
  // One listener for the panel's lifetime; it reads the latest options from the ref.
  const latest = useRef(options)
  const last = useRef<string | null>(options.selected)
  useEffect(() => {
    latest.current = options
    if (options.selected !== null) last.current = options.selected
  })

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (ownedElsewhere(event)) return
      const { order, selected, select, clear } = latest.current
      const at = selected === null ? -1 : order.indexOf(selected)
      let next: string | undefined
      switch (event.key) {
        case 'j':
        case 'ArrowDown':
          next = at < 0 ? order[0] : order[Math.min(at + 1, order.length - 1)]
          break
        case 'k':
        case 'ArrowUp':
          next = at < 0 ? order[order.length - 1] : order[Math.max(at - 1, 0)]
          break
        case 'Enter': {
          if (event.target instanceof Element && event.target.closest(ACTIVATES_ON_ENTER)) return
          if (selected !== null) {
            event.preventDefault()
            clear()
            return
          }
          const previous = last.current
          next = previous !== null && order.includes(previous) ? previous : order[0]
          break
        }
        case 'Escape':
          if (selected === null) return
          event.preventDefault()
          clear()
          return
        default:
          return
      }
      if (next === undefined) return
      // The arrows would otherwise scroll the pane under focus as well.
      event.preventDefault()
      if (next !== selected) select(next)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])
}
