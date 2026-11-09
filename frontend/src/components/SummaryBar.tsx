import type { Ref } from 'react'
import type { FieldState } from '../lib/status'
import styles from './SummaryBar.module.css'

export interface SummaryChip {
  state: FieldState
  count: number
}

interface Props {
  /** The (sticky) bar itself, so a revealed row can be scrolled clear of it. */
  barRef?: Ref<HTMLDivElement>
  /** One chip per model state (confidence levels, unknown, conflict). */
  chips: SummaryChip[]
  /** One chip per review state (accepted, rejected, edited, unreviewed). */
  reviewChips: SummaryChip[]
  active: ReadonlySet<string>
  onToggle: (key: string) => void
  riskyOnly: boolean
  riskyCount: number
  onRiskyOnly: () => void
  shown: number
  total: number
  onClear: () => void
}

/**
 * A count per model state and per review state; each chip toggles a filter. Chips of one row combine
 * with "or", the two rows with "and" (e.g. unreviewed conflicts). "Risky only" keeps conflicts and the
 * weaker confidence levels.
 */
export function SummaryBar({ barRef, chips, reviewChips, active, onToggle, riskyOnly, riskyCount, onRiskyOnly, shown, total, onClear }: Props) {
  const filtered = active.size > 0 || riskyOnly
  return (
    <div ref={barRef} className={styles.bar}>
      <div className={styles.controls}>
        <div className={styles.groups}>
          <ChipList label="Filter by state" chips={chips} active={active} onToggle={onToggle} />
          <ChipList label="Filter by review" chips={reviewChips} active={active} onToggle={onToggle} />
        </div>
        <button
          type="button"
          className={styles.risky}
          aria-pressed={riskyOnly}
          aria-label={`Risky only: ${riskyCount}`}
          onClick={onRiskyOnly}
        >
          <span aria-hidden="true" className={styles.switch} />
          <span>Risky only</span>
          <span className={styles.count}>{riskyCount}</span>
        </button>
      </div>
      {filtered && (
        <p className={styles.filterLine} aria-live="polite">
          <span>
            Showing {shown} of {total} fields
          </span>
          <button type="button" className={styles.clear} onClick={onClear}>
            Clear filters
          </button>
        </p>
      )}
    </div>
  )
}

function ChipList({ label, chips, active, onToggle }: { label: string; chips: SummaryChip[]; active: ReadonlySet<string>; onToggle: (key: string) => void }) {
  return (
    <ul className={styles.chips} aria-label={label}>
      {chips.map(({ state, count }) => {
        const on = active.has(state.key)
        return (
          <li key={state.key}>
            <button
              type="button"
              className={styles.chip}
              data-tone={state.tone}
              aria-pressed={on}
              aria-label={`${state.text}: ${count}`}
              disabled={count === 0 && !on}
              onClick={() => onToggle(state.key)}
            >
              <span aria-hidden="true" className={styles.icon}>{state.icon}</span>
              <span>{state.text}</span>
              <span className={styles.count}>{count}</span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}
