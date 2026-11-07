import { flagInfo } from '../lib/flags'
import { Icon } from './Icon'
import { Tip } from './Tip'
import styles from './Flags.module.css'

/** Icon-only flags for the rail. Each icon is an img named by its label, with a tooltip. */
export function FlagIcons({ flags }: { flags: string[] }) {
  if (flags.length === 0) return null
  return (
    <span className={styles.icons}>
      {flags.map((flag) => {
        const info = flagInfo(flag)
        const label = info?.label ?? flag
        return (
          <Tip key={flag} label={label}>
            <span
              role="img"
              aria-label={label}
              className={styles.icon}
              data-tone={info?.tone ?? 'neutral'}
            >
              <Icon name={info?.icon ?? 'flag'} size={12} />
            </span>
          </Tip>
        )
      })}
    </span>
  )
}

/** A flag as an icon + text chip; an unrecognised flag is shown as-is in a neutral chip. */
export function FlagChip({ flag }: { flag: string }) {
  const info = flagInfo(flag)
  if (!info) return <span className={`${styles.chip} ${styles.raw}`}>{flag}</span>
  return (
    <span className={styles.chip} data-tone={info.tone}>
      <Icon name={info.icon} size={14} />
      {info.label}
    </span>
  )
}

export function FlagChips({ flags }: { flags: string[] }) {
  if (flags.length === 0) return null
  return (
    <ul className={styles.chips} aria-label="Document flags">
      {flags.map((flag) => (
        <li key={flag}>
          <FlagChip flag={flag} />
        </li>
      ))}
    </ul>
  )
}
