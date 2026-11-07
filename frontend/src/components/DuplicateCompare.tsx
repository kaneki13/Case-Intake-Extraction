import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import * as Tabs from '@radix-ui/react-tabs'
import { Link, useParams, useSearchParams } from 'react-router'
import { useCatalog, useDocument, useDuplicates } from '../api/queries'
import type { CaseOutput, Catalog, CatalogField, DuplicateGroup, DuplicatePair } from '../api/types'
import { COMPARISONS, compareFieldValues, comparisonClass, type Comparison } from '../lib/compare'
import { buildCaseRows, type RowValue } from '../lib/fieldRows'
import { DocumentTextPane } from './DocumentTextPane'
import { ConfidenceBadge, joinIds } from './DuplicatesPage'
import { ErrorCard } from './states/ErrorCard'
import { Notice } from './states/Notice'
import { DocumentSkeleton, FieldsSkeleton } from './states/Skeletons'
import tabStyles from './CaseTabs.module.css'
import styles from './DuplicateCompare.module.css'

/** How each state is shown: icon + colour (comparisonClass) + text. */
const SHOWN: Record<Comparison, { icon: string; text: string }> = {
  match: { icon: '✓', text: 'Match' },
  differ: { icon: '✗', text: 'Differ' },
  'one-sided': { icon: '◌', text: 'One side only' },
  'both-absent': { icon: '·', text: 'Neither' },
}

interface CompareRow { field: CatalogField; a: RowValue; b: RowValue; state: Comparison }
interface CompareSection { id: string; label: string; rows: CompareRow[] }
type Comparing = { ok: true; sections: CompareSection[]; counts: Record<Comparison, number> } | { ok: false; error: string }

/** Both cases' rows (catalog sections and order, formatted values) with each field's comparison. */
function compareCases(catalog: Catalog, a: CaseOutput, b: CaseOutput): Comparing {
  try {
    const rowsB = new Map(buildCaseRows(catalog, b).sections.flatMap((s) => s.rows).map((r) => [r.field.id, r]))
    const counts = Object.fromEntries(COMPARISONS.map((c) => [c, 0])) as Record<Comparison, number>
    const sections = buildCaseRows(catalog, a).sections.map((s) => ({
      id: s.id,
      label: s.label,
      rows: s.rows.map((ra): CompareRow => {
        const rb = rowsB.get(ra.field.id)
        if (!rb) throw new Error(`field ${ra.field.label} has a row in one case only`)
        const state = compareFieldValues(ra.field, ra.result, rb.result)
        counts[state]++
        return { field: ra.field, a: ra.value, b: rb.value, state }
      }),
    }))
    return { ok: true, sections, counts }
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) }
  }
}

/** "14 match · 2 differ · 0 one-sided", plus the fields neither report states when there are any. */
function summaryText(counts: Record<Comparison, number>): string {
  const parts = [`${counts.match} match`, `${counts.differ} differ`, `${counts['one-sided']} one-sided`]
  if (counts['both-absent'] > 0) parts.push(`${counts['both-absent']} in neither`)
  return parts.join(' · ')
}

/** A value cell: list values as chips, absent values quiet and italic. */
function Value({ value }: { value: RowValue }) {
  if (value.kind === 'value' && value.items) {
    return (
      <span className={styles.chips}>
        {value.items.map((item, i) => (
          <span key={`${i}-${item}`} className={styles.chip}>{item}</span>
        ))}
      </span>
    )
  }
  return (
    <span className={styles.value} data-kind={value.kind} title={value.kind === 'value' ? value.title : undefined}>
      {value.text}
    </span>
  )
}

interface TableProps {
  pair: DuplicatePair
  comparing: Comparing
  fieldId: string | null
  /** The selection came from a quote: bring its row into view. */
  revealKey: number
  onToggle: (fieldId: string) => void
}

function ComparisonTable({ pair, comparing, fieldId, revealKey, onToggle }: TableProps) {
  const selectedRow = useRef<HTMLTableRowElement>(null)
  useEffect(() => {
    if (revealKey > 0) selectedRow.current?.scrollIntoView({ block: 'nearest' })
  }, [revealKey])

  if (!comparing.ok) {
    return (
      <Notice icon="alert" heading="Couldn't compare these cases">
        {comparing.error}
      </Notice>
    )
  }
  return (
    <table aria-label="Field comparison" className={styles.table}>
      <colgroup>
        <col className={styles.colLabel} />
        <col />
        <col className={styles.colState} />
        <col />
      </colgroup>
      <thead>
        <tr>
          <th scope="col">Field</th>
          <th scope="col">
            <span className={styles.side}>A</span> {pair.a.id}
          </th>
          <th scope="col">
            <span className="visually-hidden">Comparison</span>
          </th>
          <th scope="col">
            <span className={styles.side}>B</span> {pair.b.id}
          </th>
        </tr>
      </thead>
      {comparing.sections.map((section) => (
        <tbody key={section.id}>
          <tr className={styles.sectionRow}>
            <th scope="rowgroup" colSpan={4}>{section.label}</th>
          </tr>
          {section.rows.map(({ field, a, b, state }) => {
            const selected = field.id === fieldId
            return (
              <tr
                key={field.id}
                ref={selected ? selectedRow : undefined}
                className={`${styles.row} ${comparisonClass(state)}`}
                data-state={state}
                data-selected={selected || undefined}
              >
                <th scope="row" className={styles.label}>
                  <button type="button" className={styles.rowButton} aria-pressed={selected} onClick={() => onToggle(field.id)}>
                    {field.label}
                  </button>
                </th>
                <td className={styles.cell}><Value value={a} /></td>
                <td className={styles.stateCell}>
                  <span aria-hidden="true" className={styles.icon}>{SHOWN[state].icon}</span>
                  <span className="visually-hidden">{SHOWN[state].text}</span>
                </td>
                <td className={styles.cell}><Value value={b} /></td>
              </tr>
            )
          })}
        </tbody>
      ))}
    </table>
  )
}

/** One side: loads its document and shows its text, or the load's skeleton / error. */
function ComparePane({ side, docId, caseIndex, fieldId, onSelect }: {
  side: 'A' | 'B'; docId: string; caseIndex: number; fieldId: string | null; onSelect: (fieldId: string) => void
}) {
  const doc = useDocument(docId)
  if (doc.isError) {
    return (
      <div className={styles.paneState}>
        <ErrorCard
          title={`Couldn't load ${docId}`}
          error={doc.error}
          retrying={doc.isFetching}
          onRetry={() => void doc.refetch()}
        />
      </div>
    )
  }
  if (!doc.data) return <div className={styles.paneState}><DocumentSkeleton /></div>
  return (
    <DocumentTextPane detail={doc.data} caseIndex={caseIndex} fieldId={fieldId} label={`Document ${side}`} onSelect={onSelect} />
  )
}

/** The catalog, both documents and, once all are in, the comparison of the pair's two cases. */
function usePairComparison(pair: DuplicatePair) {
  const catalog = useCatalog()
  const docA = useDocument(pair.a.id)
  const docB = useDocument(pair.b.id)
  const caseA = docA.data?.extraction.cases[pair.a.caseIndex]
  const caseB = docB.data?.extraction.cases[pair.b.caseIndex]
  const comparing = useMemo(
    () => (catalog.data && caseA && caseB ? compareCases(catalog.data, caseA, caseB) : null),
    [catalog.data, caseA, caseB],
  )
  const failed = comparing && !comparing.ok ? comparing.error : null
  useEffect(() => {
    if (failed) console.error(`DuplicateCompare: ${failed}`)
  }, [failed])
  return { catalog, docA, docB, caseA, caseB, comparing }
}

/** The middle column: the field comparison, or why it cannot be shown yet. */
function CompareColumn({ pair, state, fieldId, revealKey, onToggle }: {
  pair: DuplicatePair; state: ReturnType<typeof usePairComparison>; fieldId: string | null; revealKey: number
  onToggle: (fieldId: string) => void
}) {
  const { catalog, docA, docB, caseA, caseB, comparing } = state
  if (catalog.isError) {
    return (
      <ErrorCard
        title="Couldn't load the field catalog"
        error={catalog.error}
        retrying={catalog.isFetching}
        onRetry={() => void catalog.refetch()}
      />
    )
  }
  if (docA.isError || docB.isError) {
    return (
      <Notice icon="fileX" heading="Comparison needs both reports">
        Load both documents to compare them field by field.
      </Notice>
    )
  }
  if (!catalog.data || !docA.data || !docB.data) return <FieldsSkeleton />
  const missing = !caseA ? pair.a : !caseB ? pair.b : null
  if (missing) {
    return (
      <Notice icon="alert" heading="Case not found">
        {missing.id} has no case {missing.caseIndex + 1}, so the two cases cannot be compared.
      </Notice>
    )
  }
  if (!comparing) return <FieldsSkeleton />
  return <ComparisonTable pair={pair} comparing={comparing} fieldId={fieldId} revealKey={revealKey} onToggle={onToggle} />
}

/** Legend for the state column: the icons with their words. */
function Legend() {
  return (
    <ul className={styles.legend} aria-label="Legend">
      {COMPARISONS.map((c) => (
        <li key={c} className={`${styles.legendItem} ${comparisonClass(c)}`}>
          <span aria-hidden="true" className={styles.icon}>{SHOWN[c].icon}</span>
          {SHOWN[c].text}
        </li>
      ))}
    </ul>
  )
}

const pairName = (pair: DuplicatePair) => joinIds([pair.a.id, pair.b.id])

/** The three columns for one pair, and the header line that summarises it. */
function PairView({ group, index, pair, pairIndex, onPair }: {
  group: DuplicateGroup; index: number; pair: DuplicatePair; pairIndex: number; onPair: (i: number) => void
}) {
  const [params, setParams] = useSearchParams()
  const fieldId = params.get('field') || null
  const comparison = usePairComparison(pair)
  // Bumped when a quote selects a field: the table brings that row into view (a row click needs nothing).
  // A link that arrives with a field selected reveals its row once too.
  const [revealKey, setRevealKey] = useState(() => (fieldId === null ? 0 : 1))
  const select = useCallback(
    (next: string | null, fromText = false) => {
      if (fromText) setRevealKey((k) => k + 1)
      setParams(
        (prev) => {
          const p = new URLSearchParams(prev)
          if (next === null) p.delete('field')
          else p.set('field', next)
          return p
        },
        // Field hops must not flood Back.
        { replace: true },
      )
    },
    [setParams],
  )
  const toggle = useCallback((id: string) => select(id === fieldId ? null : id), [select, fieldId])
  const fromText = useCallback((id: string) => select(id, true), [select])
  const summary = comparison.comparing?.ok ? summaryText(comparison.comparing.counts) : null

  const columns = (
    <div className={styles.columns}>
      <div className={`${styles.textColumn} ${styles.paneA}`}>
        <ComparePane side="A" docId={pair.a.id} caseIndex={pair.a.caseIndex} fieldId={fieldId} onSelect={fromText} />
      </div>
      <div className={styles.tableColumn}>
        <CompareColumn pair={pair} state={comparison} fieldId={fieldId} revealKey={revealKey} onToggle={toggle} />
      </div>
      <div className={`${styles.textColumn} ${styles.paneB}`}>
        <ComparePane side="B" docId={pair.b.id} caseIndex={pair.b.caseIndex} fieldId={fieldId} onSelect={fromText} />
      </div>
    </div>
  )

  return (
    <>
      <header className={styles.header}>
        <Link to="/duplicates" className={styles.back}>
          <span aria-hidden="true">← </span>All duplicates
        </Link>
        <div className={styles.headline}>
          <div className={styles.titleBlock}>
            <p className={styles.eyebrow}>Duplicate group {index + 1}</p>
            <h1 className={styles.title}>{pairName(pair)}</h1>
          </div>
          <ConfidenceBadge confidence={group.confidence} />
        </div>
        <p className={styles.reason}>{group.reason}</p>
        <div className={styles.summaryRow}>
          {summary !== null && <p className={styles.summary}>{summary}</p>}
          <Legend />
        </div>
      </header>
      {group.pairs.length > 1 ? (
        <Tabs.Root
          className={styles.tabs}
          value={String(pairIndex)}
          onValueChange={(v) => onPair(Number(v))}
          activationMode="manual"
        >
          <Tabs.List className={`${tabStyles.list} ${styles.tabList}`} aria-label="Document pairs">
            {group.pairs.map((p, i) => (
              <Tabs.Trigger key={i} value={String(i)} className={tabStyles.tab}>
                {pairName(p)}
              </Tabs.Trigger>
            ))}
          </Tabs.List>
          {group.pairs.map((_, i) => (
            <Tabs.Content key={i} value={String(i)} className={styles.tabContent}>
              {i === pairIndex ? columns : null}
            </Tabs.Content>
          ))}
        </Tabs.Root>
      ) : (
        columns
      )}
    </>
  )
}

/** Parse `:index` and `?pair=`: a non-negative integer below `count`, else null. */
function indexIn(raw: string | null | undefined, count: number): number | null {
  if (raw === null || raw === undefined || !/^\d+$/.test(raw)) return null
  const n = Number(raw)
  return n < count ? n : null
}

/**
 * `/duplicates/:index`: document A's text, the field-by-field comparison, document B's text.
 * A group of more than two reports offers its pairs as tabs (`?pair=`); `?field=` is the selected field.
 */
export function DuplicateCompare() {
  const { index: rawIndex } = useParams()
  const [params, setParams] = useSearchParams()
  const duplicates = useDuplicates()
  const groups = duplicates.data

  const choosePair = useCallback(
    (i: number) =>
      setParams((prev) => {
        const p = new URLSearchParams(prev)
        if (i === 0) p.delete('pair')
        else p.set('pair', String(i))
        return p
      }),
    [setParams],
  )

  const index = groups ? indexIn(rawIndex, groups.length) : null
  const group = index === null ? undefined : groups?.[index]
  const rawPair = params.get('pair')
  const pairIndex = group ? indexIn(rawPair ?? '0', group.pairs.length) : null
  const badPair = group !== undefined && rawPair !== null && pairIndex === null
  useEffect(() => {
    if (badPair) console.error(`DuplicateCompare: ?pair=${rawPair} is not a pair of this group; showing the first pair`)
  }, [badPair, rawPair])

  let body
  if (duplicates.isError) {
    body = (
      <div className={styles.state}>
        <ErrorCard
          title="Couldn't load duplicates"
          error={duplicates.error}
          retrying={duplicates.isFetching}
          onRetry={() => void duplicates.refetch()}
        />
      </div>
    )
  } else if (!groups) {
    body = (
      <div className={styles.columns} role="status" aria-busy="true" aria-label="Loading comparison">
        <div className={styles.textColumn}><div className={styles.paneState}><DocumentSkeleton /></div></div>
        <div className={styles.tableColumn}><FieldsSkeleton /></div>
        <div className={styles.textColumn}><div className={styles.paneState}><DocumentSkeleton /></div></div>
      </div>
    )
  } else if (!group) {
    body = (
      <div className={styles.state}>
        <Notice icon="copy" heading="Duplicate group not found">
          There is no duplicate group {rawIndex}. <Link to="/duplicates" className={styles.inlineLink}>See all duplicates</Link>
        </Notice>
      </div>
    )
  } else {
    const at = pairIndex ?? 0
    const pair = group.pairs[at]
    body = pair ? (
      <PairView group={group} index={index as number} pair={pair} pairIndex={at} onPair={choosePair} />
    ) : (
      <div className={styles.state}>
        <Notice icon="alert" heading="No pair to compare">
          This group lists no matched case pair.
        </Notice>
      </div>
    )
  }

  return (
    <main id="document" className={styles.page} tabIndex={-1}>
      {body}
    </main>
  )
}
