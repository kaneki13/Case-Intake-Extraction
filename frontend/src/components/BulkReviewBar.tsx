import type { BulkAction } from '../api/types'
import { ConfirmDialog } from './ConfirmDialog'
import { Icon } from './Icon'
import { Tip } from './Tip'
import styles from './BulkReviewBar.module.css'

interface Props {
  /** Unreviewed fields in the current case: what a bulk action would change. */
  unreviewed: number
  /** The case's label for the confirmation, e.g. "case 2 (Infant)"; omitted for a single-case document. */
  caseName?: string
  onBulk: (action: BulkAction) => void
  /** The Reject all confirmation is open (Reject all, or Alt+Shift+R, opens it). */
  confirming: boolean
  onConfirming: (open: boolean) => void
  /** A review is being saved: the buttons wait for it. */
  busy: boolean
  /** The last bulk action's failure, shown inline. */
  error: string | null
}

const fields = (n: number) => (n === 1 ? '1 field' : `${n} fields`)

/**
 * Accept all / Reject all for the current case. Both act on unreviewed fields only, so accepted,
 * rejected and edited fields keep their review; Reject all asks first.
 */
export function BulkReviewBar({ unreviewed, caseName, onBulk, confirming, onConfirming, busy, error }: Props) {
  // aria-disabled, not disabled: the button keeps focus after the last bulk action empties the case.
  const none = unreviewed === 0
  const scope = caseName ? ` in ${caseName}` : ''
  return (
    <div className={styles.bar}>
      <div role="group" aria-label="Review all unreviewed fields" className={styles.buttons}>
        <Tip label={`Accept the ${fields(unreviewed)} not yet reviewed${scope} (Alt+Shift+A)`}>
          <button
            type="button"
            className={styles.button}
            data-action="accept"
            aria-keyshortcuts="Alt+Shift+A"
            aria-disabled={none || busy || undefined}
            onClick={() => {
              if (!busy && !none) onBulk('accept')
            }}
          >
            <Icon name="check" size={14} />
            Accept all
          </button>
        </Tip>
        <Tip label={`Reject the ${fields(unreviewed)} not yet reviewed${scope} (Alt+Shift+R)`}>
          <button
            type="button"
            className={styles.button}
            data-action="reject"
            aria-keyshortcuts="Alt+Shift+R"
            aria-haspopup="dialog"
            aria-disabled={none || busy || undefined}
            onClick={() => {
              if (!busy && !none) onConfirming(true)
            }}
          >
            <Icon name="ban" size={14} />
            Reject all
          </button>
        </Tip>
      </div>
      {error && (
        <p role="alert" className={styles.error}>
          <span aria-hidden="true">! </span>
          {error}
        </p>
      )}
      <ConfirmDialog
        open={confirming}
        title={`Reject ${fields(unreviewed)}?`}
        confirmText={`Reject ${fields(unreviewed)}`}
        tone="danger"
        onCancel={() => onConfirming(false)}
        onConfirm={() => {
          onConfirming(false)
          onBulk('reject')
        }}
      >
        <p>
          This rejects the {fields(unreviewed)}{scope} that have no review yet. Accepted, rejected and
          edited fields keep their review. Reset undoes a rejection field by field.
        </p>
      </ConfirmDialog>
    </div>
  )
}
