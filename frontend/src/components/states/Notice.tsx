import { useId, type ReactNode } from 'react'
import { Icon, type IconName } from '../Icon'
import styles from './Notice.module.css'

interface Props {
  icon: IconName
  heading: string
  /** One line saying what this means for the reviewer. */
  children: ReactNode
  /** Optional detail under the line (reasons, flags). */
  detail?: ReactNode
}

/** A calm, deliberate empty state: icon, heading, one line, optional detail. Not an error. */
export function Notice({ icon, heading, children, detail }: Props) {
  const headingId = useId()
  return (
    <section className={styles.notice} aria-labelledby={headingId}>
      <span className={styles.icon}>
        <Icon name={icon} size={22} />
      </span>
      <h2 id={headingId} className={styles.heading}>{heading}</h2>
      <p className={styles.line}>{children}</p>
      {detail && <div className={styles.detail}>{detail}</div>}
    </section>
  )
}
