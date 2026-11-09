import type { Review } from '../api/types'
import { Icon, type IconName } from './Icon'
import { Tip } from './Tip'
import styles from './ReviewButtons.module.css'

export type FieldAction = 'accept' | 'reject' | 'reset'

interface Props {
  /** The field's stored review; Reset is offered only when there is one. */
  review: Review | null
  onAction: (action: FieldAction) => void
  /** A review of this field is being saved: the buttons wait for it. */
  busy: boolean
  /** The group's accessible name, e.g. "Review <field label>". */
  label: string
  /** `compact` (on the selected row): icon-only buttons. `full` (in the inspector): icon and text. */
  variant: 'compact' | 'full'
}

interface ButtonSpec {
  action: FieldAction
  text: string
  icon: IconName
  /** The key S9 binds; shown in the tooltip and announced through aria-keyshortcuts. */
  keys?: string
  tip: string
}

const BUTTONS: ButtonSpec[] = [
  { action: 'accept', text: 'Accept this', icon: 'check', keys: 'Alt+A', tip: 'Accept the model value' },
  { action: 'reject', text: 'Reject this', icon: 'ban', keys: 'Alt+R', tip: 'Reject the model value' },
  { action: 'reset', text: 'Reset', icon: 'undo', tip: 'Undo the review: back to unreviewed' },
]

/** Accept this / Reject this / Reset for one field. Every button has a text name and, where bound, a shortcut. */
export function ReviewButtons({ review, onAction, busy, label, variant }: Props) {
  const buttons = BUTTONS.filter((b) => b.action !== 'reset' || review !== null)
  return (
    <div role="group" aria-label={label} className={styles.group} data-variant={variant}>
      {buttons.map((b) => {
        const current = (b.action === 'accept' && review?.state === 'accepted') || (b.action === 'reject' && review?.state === 'rejected')
        const tip = b.keys ? `${b.tip} (${b.keys})` : b.tip
        return (
          <Tip key={b.action} label={tip}>
            <button
              type="button"
              className={styles.button}
              data-action={b.action}
              data-current={current || undefined}
              aria-label={variant === 'compact' ? b.text : undefined}
              aria-keyshortcuts={b.keys}
              aria-disabled={busy || undefined}
              onClick={() => {
                if (!busy) onAction(b.action)
              }}
            >
              <Icon name={b.icon} size={14} />
              {variant === 'full' && <span>{b.text}</span>}
            </button>
          </Tip>
        )
      })}
    </div>
  )
}
