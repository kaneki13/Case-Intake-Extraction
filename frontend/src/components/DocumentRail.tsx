import { NavLink } from 'react-router'
import { useCatalog, useDocuments, useDuplicates } from '../api/queries'
import type { DocumentSummary } from '../api/types'
import { riskCount } from '../lib/risk'
import { FlagIcons } from './Flags'
import { Icon } from './Icon'
import { ProgressRing } from './ProgressRing'
import { RiskPill, riskLabel } from './RiskPill'
import { ErrorCard } from './states/ErrorCard'
import { RailSkeleton } from './states/Skeletons'
import { caseText } from '../lib/format'
import styles from './DocumentRail.module.css'

/** The rail's tooltip: what counts as risky, in the catalog's own level names. */
function riskTip(count: number, strongest: string): string {
  return count === 0
    ? `No conflicts and nothing below ${strongest} confidence`
    : `${riskLabel(count)}: conflicts plus values below ${strongest} confidence`
}

function RailItem({ doc, levels }: { doc: DocumentSummary; levels: string[] }) {
  const risky = riskCount(doc.reviewCounts, levels)
  return (
    <li>
      <NavLink to={`/documents/${encodeURIComponent(doc.id)}`} className={styles.item}>
        <span className={styles.id}>{doc.id}</span>
        <RiskPill count={risky} tip={riskTip(risky, levels[0] ?? 'the top')} className={styles.risk} />
        <span className={styles.title} title={doc.title}>
          {doc.title}
        </span>
        <span className={styles.meta}>
          <span className={styles.cases} data-none={doc.caseCount === 0 || undefined}>
            {caseText(doc.caseCount)}
          </span>
          <FlagIcons flags={doc.flags} />
          <ProgressRing progress={doc.progress} className={styles.ring} />
        </span>
      </NavLink>
    </li>
  )
}

/**
 * The rail's way to the duplicate review: "Duplicates (n)". The count is left out while it loads; a failed
 * load says so (the page itself shows the error and its retry).
 */
function DuplicatesLink() {
  const duplicates = useDuplicates()
  const count = duplicates.data?.length
  const name = duplicates.isError
    ? "Duplicates (couldn't load)"
    : count === undefined ? 'Duplicates' : `Duplicates (${count})`
  return (
    <nav aria-label="Duplicate review" className={styles.footer}>
      <NavLink to="/duplicates" className={styles.duplicates} aria-label={name}>
        <Icon name="copy" size={16} className={styles.duplicatesIcon} />
        <span className={styles.duplicatesText}>Duplicates</span>
        {duplicates.isError ? (
          <Icon name="alert" size={14} className={styles.duplicatesError} />
        ) : (
          count !== undefined && (
            <span className={styles.duplicatesCount} data-some={count > 0 || undefined}>{count}</span>
          )
        )}
      </NavLink>
    </nav>
  )
}

/** Left rail: every document, the active one marked, with case count, flags and risk count; then the duplicates link. */
export function DocumentRail() {
  const documents = useDocuments()
  const catalog = useCatalog()

  let body
  if (documents.isError || catalog.isError) {
    const failed = documents.isError ? documents : catalog
    body = (
      <ErrorCard
        compact
        title={documents.isError ? "Couldn't load documents" : "Couldn't load the field catalog"}
        error={failed.error}
        retrying={failed.isFetching}
        onRetry={() => {
          if (documents.isError) void documents.refetch()
          if (catalog.isError) void catalog.refetch()
        }}
      />
    )
  } else if (!documents.data || !catalog.data) {
    body = <RailSkeleton />
  } else if (documents.data.length === 0) {
    body = <p className={styles.empty}>No documents have been processed yet.</p>
  } else {
    const levels = catalog.data.confidence_levels
    body = (
      <ul className={styles.list}>
        {documents.data.map((doc) => (
          <RailItem key={doc.id} doc={doc} levels={levels} />
        ))}
      </ul>
    )
  }

  return (
    <div className={styles.rail}>
      <nav aria-label="Documents" className={styles.documents}>
        <div className={styles.header}>
          <h2 className={styles.heading}>Documents</h2>
          {documents.data && <span className={styles.count}>{documents.data.length}</span>}
        </div>
        {body}
      </nav>
      <DuplicatesLink />
    </div>
  )
}
