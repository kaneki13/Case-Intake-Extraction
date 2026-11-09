import { useEffect, useId, useRef, type ReactNode } from 'react'
import styles from './ConfirmDialog.module.css'

interface Props {
  open: boolean
  title: string
  children: ReactNode
  /** The confirming button's text: says exactly what happens, e.g. "Reject 12 fields". */
  confirmText: string
  /** `danger` colours the confirming button with the risk tone. */
  tone?: 'danger' | 'default'
  onConfirm: () => void
  onCancel: () => void
}

/**
 * A modal confirmation on the native <dialog>: showModal() traps focus and makes the page inert,
 * Esc cancels, and focus returns to whatever opened it. Cancel is focused first, so Enter never
 * confirms by accident.
 */
export function ConfirmDialog({ open, title, children, confirmText, tone = 'default', onConfirm, onCancel }: Props) {
  const ref = useRef<HTMLDialogElement>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const opener = useRef<HTMLElement | null>(null)
  const titleId = useId()
  const bodyId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      dialog.showModal()
      cancelRef.current?.focus()
    } else if (!open && dialog.open) {
      dialog.close()
      opener.current?.focus()
      opener.current = null
    }
  }, [open])

  return (
    <dialog
      ref={ref}
      className={styles.dialog}
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      // Esc: the browser fires cancel; the parent decides (and closes through `open`).
      onCancel={(e) => {
        e.preventDefault()
        onCancel()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault()
          e.stopPropagation()
          onCancel()
        }
      }}
    >
      <h2 id={titleId} className={styles.title}>{title}</h2>
      <div id={bodyId} className={styles.body}>{children}</div>
      <div className={styles.actions}>
        <button ref={cancelRef} type="button" className={styles.button} onClick={onCancel}>
          Cancel
        </button>
        <button type="button" className={styles.button} data-tone={tone} onClick={onConfirm}>
          {confirmText}
        </button>
      </div>
    </dialog>
  )
}
