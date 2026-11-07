import type { ReactNode } from 'react'
import styles from './Skeletons.module.css'

/** One placeholder bar. Widths are computed per row, so they are the one inline style here. */
function Bar({ width, className }: { width: string; className?: string }) {
  return <span className={`${styles.bar} ${className ?? ''}`} style={{ width }} />
}

function Busy({ label, className, children }: { label: string; className?: string; children: ReactNode }) {
  return (
    <div role="status" aria-busy="true" aria-label={label} className={className}>
      {children}
    </div>
  )
}

const RAIL_TITLES = ['78%', '64%', '86%', '58%', '72%', '81%', '67%']

/** Shaped like DocumentRail items: id + risk pill, one-line title, meta line. */
export function RailSkeleton() {
  return (
    <Busy label="Loading documents" className={styles.rail}>
      {RAIL_TITLES.map((title, i) => (
        <div key={i} className={styles.railItem}>
          <span className={styles.railTop}>
            <Bar width="36px" className={styles.thin} />
            <Bar width="28px" className={styles.pill} />
          </span>
          <Bar width={title} />
          <Bar width="44%" className={styles.thin} />
        </div>
      ))}
    </Busy>
  )
}

// A paragraph rhythm: full lines with ragged ends, a short closing line, then a gap.
const DOC_LINES = ['97%', '100%', '94%', '99%', '61%', '', '100%', '96%', '98%', '92%', '100%', '38%', '', '95%', '99%', '72%']

/** Shaped like DocumentView: eyebrow id, title, chips, then a reading column of text. */
export function DocumentSkeleton() {
  return (
    <Busy label="Loading document" className={styles.doc}>
      <div className={styles.docHeader}>
        <Bar width="48px" className={styles.thin} />
        <Bar width="56%" className={styles.heading} />
        <span className={styles.chipRow}>
          <Bar width="64px" className={styles.chip} />
          <Bar width="132px" className={styles.chip} />
        </span>
      </div>
      <div className={styles.docText}>
        {DOC_LINES.map((w, i) => (w ? <Bar key={i} width={w} className={styles.line} /> : <span key={i} className={styles.gap} />))}
      </div>
    </Busy>
  )
}

const FIELD_SECTIONS: string[][] = [
  ['34%', '46%', '28%', '52%', '40%'],
  ['30%', '58%', '44%', '36%'],
]

/** Shaped like FieldsPanel: summary chips, then sections of 36px rows (label, value, badge). */
export function FieldsSkeleton() {
  return (
    <Busy label="Loading fields" className={styles.fields}>
      <span className={styles.chipRow}>
        {['52px', '60px', '44px', '64px', '60px', '52px'].map((w, i) => (
          <Bar key={i} width={w} className={styles.chip} />
        ))}
      </span>
      {FIELD_SECTIONS.map((rows, s) => (
        <div key={s} className={styles.section}>
          <Bar width="72px" className={styles.thin} />
          {rows.map((value, r) => (
            <span key={r} className={styles.row}>
              <Bar width="96px" />
              <Bar width={value} />
              <Bar width="56px" className={styles.pill} />
            </span>
          ))}
        </div>
      ))}
    </Busy>
  )
}

const GROUP_REASONS = ['74%', '58%', '66%']

/** Shaped like DuplicatesPage cards: document ids + confidence pill, a reason line, a meta line. */
export function DuplicatesSkeleton() {
  return (
    <Busy label="Loading duplicates" className={styles.groups}>
      {GROUP_REASONS.map((reason, i) => (
        <div key={i} className={styles.group}>
          <span className={styles.railTop}>
            <Bar width="132px" className={styles.thin} />
            <Bar width="96px" className={styles.pill} />
          </span>
          <Bar width={reason} />
          <Bar width="28%" className={styles.thin} />
        </div>
      ))}
    </Busy>
  )
}
