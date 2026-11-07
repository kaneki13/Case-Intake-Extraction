import { useId, type ReactNode } from 'react'
import styles from './SectionGroup.module.css'

interface Props {
  /** The catalog section's label. */
  label: string
  /** Rows shown / rows in the section; the count line appears only when a filter hides some. */
  shown: number
  total: number
  children: ReactNode
}

/** One catalog section: a heading and its field rows. */
export function SectionGroup({ label, shown, total, children }: Props) {
  const headingId = useId()
  return (
    <section className={styles.section} aria-labelledby={headingId}>
      <div className={styles.head}>
        <h3 id={headingId} className={styles.heading}>{label}</h3>
        <span className={styles.count} aria-hidden="true">
          {shown === total ? total : `${shown} of ${total}`}
        </span>
      </div>
      <ul className={styles.rows}>{children}</ul>
    </section>
  )
}
