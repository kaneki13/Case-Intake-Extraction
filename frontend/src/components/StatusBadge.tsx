import type { FieldState } from '../lib/status'
import { Tip } from './Tip'
import styles from './StatusBadge.module.css'

interface Props {
  state: FieldState
  /** The model's full note, shown in a tooltip on hover (the inspector shows it to everyone). */
  note?: string
  /** A smaller badge, for the model's state shown beside a review. */
  small?: boolean
}

/** Icon + colour + text for one field state; never colour alone. */
export function StatusBadge({ state, note, small }: Props) {
  const badge = (
    <span role="img" aria-label={state.name} className={styles.badge} data-tone={state.tone} data-size={small ? 'small' : undefined}>
      <span aria-hidden="true" className={styles.icon}>{state.icon}</span>
      <span aria-hidden="true" className={styles.text}>{state.text}</span>
    </span>
  )
  return note ? <Tip label={note}>{badge}</Tip> : badge
}
