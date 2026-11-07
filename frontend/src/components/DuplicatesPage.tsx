import { Link } from 'react-router'
import { useDocuments, useDuplicates } from '../api/queries'
import type { DocumentSummary, DuplicateGroup } from '../api/types'
import type { FieldState } from '../lib/status'
import { Icon } from './Icon'
import { StatusBadge } from './StatusBadge'
import { ErrorCard } from './states/ErrorCard'
import { Notice } from './states/Notice'
import { DuplicatesSkeleton } from './states/Skeletons'
import styles from './DuplicatesPage.module.css'

const CONFIDENCE: Record<DuplicateGroup['confidence'], Pick<FieldState, 'tone' | 'icon'>> = {
  high: { tone: 'ok', icon: '✓' },
  medium: { tone: 'caution', icon: '◐' },
  low: { tone: 'risk', icon: '!' },
}

/** A group's confidence as a badge: icon + colour + text, like the field badges. */
export function ConfidenceBadge({ confidence }: { confidence: DuplicateGroup['confidence'] }) {
  const text = `${confidence[0]?.toUpperCase() ?? ''}${confidence.slice(1)} confidence`
  const state: FieldState = { key: `duplicate:${confidence}`, text, name: text, ...CONFIDENCE[confidence] }
  return <StatusBadge state={state} />
}

/** "A and B", "A, B and C". */
export function joinIds(ids: string[]): string {
  if (ids.length < 2) return ids.join('')
  return `${ids.slice(0, -1).join(', ')} and ${ids[ids.length - 1] as string}`
}

/** The ids as a link's text: each id set as an id, the joining words quieter. The link is named by joinIds(ids). */
function IdList({ ids }: { ids: string[] }) {
  return (
    <>
      {ids.map((id, i) => (
        <span key={id}>
          {i > 0 && <span className={styles.and}>{i === ids.length - 1 ? ' and ' : ', '}</span>}
          <span className={styles.docId}>{id}</span>
        </span>
      ))}
    </>
  )
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

function GroupCard({ group, index, titles }: { group: DuplicateGroup; index: number; titles: Map<string, string> }) {
  // A title adds something only when it is not just the id again.
  const named = group.documentIds
    .map((id) => ({ id, title: titles.get(id) }))
    .filter((d): d is { id: string; title: string } => d.title !== undefined && d.title !== d.id)
  const agreements = Math.max(0, ...group.pairs.map((p) => p.agreements))
  return (
    <li className={styles.card}>
      <div className={styles.cardTop}>
        <h2 className={styles.ids}>
          <Link to={`/duplicates/${index}`} className={styles.link} aria-label={joinIds(group.documentIds)}>
            <IdList ids={group.documentIds} />
          </Link>
        </h2>
        <ConfidenceBadge confidence={group.confidence} />
      </div>
      {named.length > 0 && (
        <ul className={styles.titles}>
          {named.map((d) => (
            <li key={d.id} className={styles.titleLine} title={d.title}>
              <span className={styles.titleId}>{d.id}</span>
              {d.title}
            </li>
          ))}
        </ul>
      )}
      <p className={styles.reason}>{group.reason}</p>
      <p className={styles.footer}>
        <span>
          {plural(group.documentIds.length, 'report', 'reports')}
          <span aria-hidden="true"> · </span>
          <span className="visually-hidden">, </span>
          {plural(agreements, 'agreeing field', 'agreeing fields')}
        </span>
        <span className={styles.open} aria-hidden="true">
          Compare
          <span className={styles.arrow}>→</span>
        </span>
      </p>
    </li>
  )
}

/** `/duplicates`: every group of reports that appear to describe the same case. */
export function DuplicatesPage() {
  const duplicates = useDuplicates()
  const documents = useDocuments()
  const titles = new Map((documents.data ?? []).map((d: DocumentSummary) => [d.id, d.title]))
  const groups = duplicates.data

  let body
  if (duplicates.isError) {
    body = (
      <ErrorCard
        title="Couldn't load duplicates"
        error={duplicates.error}
        retrying={duplicates.isFetching}
        onRetry={() => void duplicates.refetch()}
      />
    )
  } else if (!groups) {
    body = <DuplicatesSkeleton />
  } else if (groups.length === 0) {
    body = (
      <Notice icon="copy" heading="No duplicate cases found">
        No two processed reports describe the same case.
      </Notice>
    )
  } else {
    body = (
      <ul className={styles.list}>
        {groups.map((group, i) => (
          <GroupCard key={group.documentIds.join(' ')} group={group} index={i} titles={titles} />
        ))}
      </ul>
    )
  }

  return (
    <main id="document" className={styles.page} tabIndex={-1}>
      <div className={styles.column}>
        <header className={styles.header}>
          <p className={styles.eyebrow}>
            <Icon name="copy" size={14} />
            Duplicate review
            {groups && groups.length > 0 && (
              <span className={styles.count}>
                <span aria-hidden="true">· </span>
                {plural(groups.length, 'group', 'groups')}
              </span>
            )}
          </p>
          <h1 className={styles.title}>Possible duplicates</h1>
          <p className={styles.lede}>
            Reports that appear to describe the same case, found by comparing every pair of cases field by field.
            Open a group to compare its reports side by side.
          </p>
        </header>
        {body}
      </div>
    </main>
  )
}
