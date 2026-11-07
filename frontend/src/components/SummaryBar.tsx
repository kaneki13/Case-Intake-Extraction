import type { Ref } from 'react'
import type { FieldState } from '../lib/status'
import styles from './SummaryBar.module.css'

export interface SummaryChip {
  state: FieldState
  count: number
  /** Reserved chips (edited) are hidden while their count is 0. */
  hideWhenEmpty?: boolean
}

interface Props {
  /** The (sticky) bar itself, so a revealed row can be scrolled clear of it. */
  barRef?: Ref<HTMLDivElement>
  chips: SummaryChip[]
  active: ReadonlySet<string>
  onToggle: (key: string) => void
  riskyOnly: boolean
  riskyCount: number
  onRiskyOnly: () => void
  shown: number
  total: number
  onClear: () => void
}

/** A count per state; each chip toggles a filter. "Risky only" keeps conflicts and the weaker confidence levels. */
export function SummaryBar({ barRef, chips, active, onToggle, riskyOnly, riskyCount, onRiskyOnly, shown, total, onClear }: Props) {
  const filtered = active.size > 0 || riskyOnly
  return (
    <div ref={barRef} className={styles.bar}>
      <div className={styles.controls}>
        <ul className={styles.chips} aria-label="Filter by state">
          {chips
            .filter((c) => !(c.hideWhenEmpty && c.count === 0 && !active.has(c.state.key)))
            .map(({ state, count }) => {
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
