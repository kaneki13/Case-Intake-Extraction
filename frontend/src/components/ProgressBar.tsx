import type { Progress } from '../api/types'
import styles from './ProgressBar.module.css'

export const NOTHING_TO_REVIEW = 'Nothing to review'

/** "12 of 32 reviewed": the accessible wording of a document's progress. */
export const progressLabel = ({ reviewed, total }: Progress): string => `${reviewed} of ${total} reviewed`

/** Reviewed share as a whole percent (Q8: fields_reviewed / total_fields × 100). */
export const progressPercent = ({ reviewed, total }: Progress): number =>
  total === 0 ? 0 : Math.round((reviewed / total) * 100)

/**
 * A document's review progress: a bar and "12 / 32 reviewed". A document with nothing to review
 * (no case) shows that sentence instead of an empty bar.
 */
export function ProgressBar({ progress, label = 'Review progress' }: { progress: Progress; label?: string }) {
  const { reviewed, total } = progress
  if (total === 0) return <span className={styles.none}>{NOTHING_TO_REVIEW}</span>
  const percent = progressPercent(progress)
  return (
    <span className={styles.wrap} data-done={reviewed >= total || undefined}>
      <span
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={reviewed}
        aria-valuetext={`${progressLabel(progress)} (${percent}%)`}
        className={styles.track}
      >
        <span className={styles.fill} style={{ width: `${percent}%` }} />
      </span>
      <span className={styles.text}>
        {reviewed} / {total} reviewed
      </span>
    </span>
  )
}
