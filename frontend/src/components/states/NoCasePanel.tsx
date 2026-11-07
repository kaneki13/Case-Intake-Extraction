import { useEffect } from 'react'
import { flagInfo, isNoCaseReason } from '../../lib/flags'
import { Icon } from '../Icon'
import { Notice } from './Notice'
import styles from './NoCasePanel.module.css'

/**
 * A document whose extraction holds no case. The reasons come from the flags
 * (FRONTEND.md §4.7); any other flag is shown as-is in a neutral chip.
 */
export function NoCasePanel({ docId, flags }: { docId: string; flags: string[] }) {
  const reasons = flags.filter(isNoCaseReason)
  const others = flags.filter((f) => !isNoCaseReason(f))
  const noFlags = flags.length === 0

  useEffect(() => {
    if (noFlags) console.error(`NoCasePanel: document ${docId} has no cases and no flags explaining why`)
  }, [docId, noFlags])

  const detail = noFlags ? (
    <p className={styles.missing}>The extraction gave no reason.</p>
  ) : (
    <>
      <p className={styles.eyebrow}>{reasons.length > 0 ? 'Why' : 'Flags'}</p>
      {reasons.length > 0 && (
        <ul className={styles.reasons}>
          {reasons.map((flag) => {
            const info = flagInfo(flag)
            return (
              <li key={flag} className={styles.reason}>
                <Icon name={info?.icon ?? 'flag'} size={16} />
                {info?.label ?? flag}
              </li>
            )
          })}
        </ul>
      )}
      {others.length > 0 && (
        <ul className={styles.others} aria-label="Other flags">
          {others.map((flag) => (
            <li key={flag}>
              {/* §4.7: flags outside the no-case reasons are shown as-is */}
              <span className={styles.raw}>{flag}</span>
            </li>
          ))}
        </ul>
      )}
    </>
  )

  return (
    <Notice icon="inbox" heading="No case to review" detail={detail}>
      The extraction found no reportable case in this document.
    </Notice>
  )
}
