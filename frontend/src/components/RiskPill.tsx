import { Tip } from './Tip'
import styles from './RiskPill.module.css'

/** "No risky values" / "1 risky value" / "N risky values". */
export const riskLabel = (count: number): string =>
  count === 0 ? 'No risky values' : count === 1 ? '1 risky value' : `${count} risky values`

interface Props {
  count: number
  /** A longer explanation shown on hover/focus; the pill's own label stays its accessible name. */
  tip?: string
  /** Placement in the parent's layout. */
  className?: string
}

/**
 * The risky-value count as glyph, colour and number together: "!" and the count when there is
 * something to check, "✓ 0" when there is not. Named for screen readers by `riskLabel`.
 */
export function RiskPill({ count, tip, className }: Props) {
  const label = riskLabel(count)
  const pill = (
    <span
      role="img"
      aria-label={label}
      className={className ? `${styles.pill} ${className}` : styles.pill}
      data-none={count === 0 || undefined}
    >
      <span aria-hidden="true" className={styles.glyph}>
        {count === 0 ? '✓' : '!'}
      </span>
      <span aria-hidden="true">{count}</span>
    </span>
  )
  return tip ? <Tip label={tip}>{pill}</Tip> : pill
}
