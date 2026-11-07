import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useLocation, useParams } from 'react-router'
import { FIELDS_PANE_ID } from '../hooks/useFieldKeyboard'
import { revealOf } from '../hooks/useSelection'
import styles from './Panes.module.css'

/**
 * The document (main) and fields (aside) panes, each scrolling on its own.
 * Below 960px the aside becomes a bottom sheet with a sticky handle that folds it away.
 */
export function Panes({ document, fields }: { document: ReactNode; fields: ReactNode }) {
  const { id } = useParams()
  const mainRef = useRef<HTMLElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const [sheetOpen, setSheetOpen] = useState(true)
  const sheetId = useId()

  // A field selected from the keyboard or the text unfolds a folded sheet, so its row can be shown.
  // Adjusted during render, so the row is visible by the time the panel reveals it.
  const location = useLocation()
  const [seenKey, setSeenKey] = useState(location.key)
  if (location.key !== seenKey) {
    setSeenKey(location.key)
    if (!sheetOpen && revealOf(location.state)) setSheetOpen(true)
  }

  // A new document starts both panes at the top; the scroll positions of the last one mean nothing here.
  // Not when a field is selected (a deep link, or Back to a selection): its quote scroll and row reveal
  // run first (child effects), and a reset would undo them.
  const fieldSelected = new URLSearchParams(location.search).has('field')
  useEffect(() => {
    if (fieldSelected) return
    if (mainRef.current) mainRef.current.scrollTop = 0
    if (bodyRef.current) bodyRef.current.scrollTop = 0
    // Once per document: the selection read here is the one it opened with.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  return (
    <>
      <main id="document" ref={mainRef} className={styles.main} tabIndex={-1}>
        {document}
      </main>
      <aside id={FIELDS_PANE_ID} aria-label="Extracted fields" className={styles.aside} data-open={sheetOpen}>
        <button
          type="button"
          className={styles.handle}
          aria-expanded={sheetOpen}
          aria-controls={sheetId}
          onClick={() => setSheetOpen((o) => !o)}
        >
          <span className={styles.grabber} aria-hidden="true" />
          <span>{sheetOpen ? 'Hide fields' : 'Show fields'}</span>
        </button>
        <div id={sheetId} ref={bodyRef} className={styles.body}>
          {fields}
        </div>
      </aside>
    </>
  )
}
