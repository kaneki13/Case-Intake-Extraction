import type { Progress } from '../api/types'
import { progressLabel } from './ProgressBar'
import styles from './ProgressRing.module.css'

const SIZE = 18
const STROKE = 2.5
const R = (SIZE - STROKE) / 2
const C = 2 * Math.PI * R

/** A small ring for one document's review progress in the rail, named "n of m reviewed". Nothing when there is nothing to review. */
export function ProgressRing({ progress, className }: { progress: Progress; className?: string }) {
  const { reviewed, total } = progress
  if (total === 0) return null
  const share = Math.min(Math.max(reviewed / total, 0), 1)
  const label = progressLabel(progress)
  return (
    <span role="img" aria-label={label} title={label} className={className ? `${styles.ring} ${className}` : styles.ring} data-done={share >= 1 || undefined}>
      <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true" focusable="false">
        <circle className={styles.track} cx={SIZE / 2} cy={SIZE / 2} r={R} strokeWidth={STROKE} fill="none" />
        <circle
          className={styles.value}
          cx={SIZE / 2}
          cy={SIZE / 2}
          r={R}
          strokeWidth={STROKE}
          fill="none"
          strokeDasharray={C}
          strokeDashoffset={C * (1 - share)}
          strokeLinecap="round"
          transform={`rotate(-90 ${SIZE / 2} ${SIZE / 2})`}
        />
      </svg>
      {share >= 1 && <span aria-hidden="true" className={styles.check}>✓</span>}
    </span>
  )
}
