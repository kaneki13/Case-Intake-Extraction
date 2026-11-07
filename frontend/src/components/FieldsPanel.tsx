import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useCatalog, useDocument } from '../api/queries'
import type { CaseOutput, Catalog, DocumentDetail } from '../api/types'
import { useFieldKeyboard } from '../hooks/useFieldKeyboard'
import { useQuoteModel } from '../hooks/useQuoteModel'
import { useSelection } from '../hooks/useSelection'
import { draftKey, useDrafts } from '../lib/drafts'
import { buildCaseRows, withDraft, type FieldRowModel, type SectionModel } from '../lib/fieldRows'
import { isRisky } from '../lib/risk'
import { conflictState, editedState, levelState, unknownState } from '../lib/status'
import { CaseTabs } from './CaseTabs'
import { FieldInspector } from './FieldInspector'
import { FieldRow } from './FieldRow'
import { SectionGroup } from './SectionGroup'
import { SummaryBar, type SummaryChip } from './SummaryBar'
import { ErrorCard } from './states/ErrorCard'
import { NoCasePanel } from './states/NoCasePanel'
import { FieldsSkeleton } from './states/Skeletons'
import styles from './FieldsPanel.module.css'

/** Right pane: the extracted fields of the selected case. */
export function FieldsPanel() {
  const { docId } = useSelection()
  const doc = useDocument(docId)
  const catalog = useCatalog()

  // The document pane carries the error card and retry; repeating it here would double the alert.
  if (doc.isError) return <p className={styles.unavailable}>Fields appear once the document loads.</p>
  if (catalog.isError) {
    return (
      <div className={styles.errorWrap}>
        <ErrorCard
          compact
          title="Couldn't load the field catalog"
          error={catalog.error}
          retrying={catalog.isFetching}
          onRetry={() => void catalog.refetch()}
        />
      </div>
    )
  }
  if (!doc.data || !catalog.data) return <FieldsSkeleton />

  const { cases, flags } = doc.data.extraction
  if (cases.length === 0) return <NoCasePanel docId={doc.data.id} flags={flags ?? []} />
  // Keyed by document: filters are local to the panel and start fresh on every document.
  return <CaseFields key={doc.data.id} doc={doc.data} catalog={catalog.data} />
}

function CaseFields({ doc, catalog }: { doc: DocumentDetail; catalog: Catalog }) {
  const { caseIndex, setCase, select, reveal } = useSelection()
  const { selectedFieldId, invalidByField } = useQuoteModel()
  const levels = catalog.confidence_levels
  const cases = doc.extraction.cases
  // useSelection has already validated the index against this document.
  const shownIndex = cases[caseIndex] ? caseIndex : 0
  const kase = cases[shownIndex] as CaseOutput
  const drafts = useDrafts()

  const model = useMemo(() => buildCaseRows(catalog, kase), [catalog, kase])
  // The rows as shown: a field with a local draft shows the draft, marked edited, with the model state beside it.
  const sections = useMemo<SectionModel[]>(
    () =>
      model.sections.map((s) => ({
        ...s,
        rows: s.rows.map((r) => {
          const key = draftKey(doc.id, shownIndex, r.field.id)
          return drafts.has(key) ? withDraft(r, drafts.get(key) ?? null) : r
        }),
      })),
    [model, drafts, doc.id, shownIndex],
  )
  const riskCounts = useMemo(
    () =>
      cases.map(
        (c) =>
          catalog.fields.filter((f) => {
            const r = c.fields[f.id]
            return r !== undefined && isRisky(r, catalog.confidence_levels)
          }).length,
      ),
    [cases, catalog],
  )

  const [active, setActive] = useState<ReadonlySet<string>>(() => new Set())
  const [riskyOnly, setRiskyOnly] = useState(false)

  useEffect(() => {
    if (model.problems.length > 0) {
      console.error(`FieldsPanel: ${doc.id} case ${caseIndex + 1} has unexpected data`, model.problems)
    }
    // Once per document and case: model changes exactly then.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model])

  const allRows = useMemo(() => sections.flatMap((s) => s.rows), [sections])
  const chips = useMemo<SummaryChip[]>(() => {
    const count = (key: string) => allRows.filter((r) => r.state.key === key).length
    const base = [...levels.map((l) => levelState(l, levels)), unknownState(), conflictState()]
    const chips: SummaryChip[] = base.map((state) => ({ state, count: count(state.key) }))
    // The edited chip is added last, on its own terms (hidden at 0).
    const edited = editedState()
    // A level the catalog does not list still gets a chip (risk-toned) rather than vanishing from the counts.
    const seen = new Set([...base.map((s) => s.key), edited.key])
    for (const r of allRows) {
      if (!seen.has(r.state.key)) {
        seen.add(r.state.key)
        chips.push({ state: r.state, count: count(r.state.key) })
      }
    }
    chips.push({ state: edited, count: count(edited.key), hideWhenEmpty: true })
    return chips
  }, [allRows, levels])

  const matches = (r: FieldRowModel) => (active.size === 0 || active.has(r.state.key)) && (!riskyOnly || r.risky)
  // The selected row stays visible under any filter: the reviewer chose it (e.g. by clicking a quote).
  const visible = (r: FieldRowModel) => matches(r) || r.field.id === selectedFieldId
  // The keyboard's path: visible rows of this case, in display order.
  const order = allRows.filter(visible).map((r) => r.field.id)
  const shown = order.length

  // The selected row's button, for revealing it and for keeping focus when its inspector closes.
  const selectedRow = useRef<HTMLButtonElement>(null)
  const summaryBar = useRef<HTMLDivElement>(null)
  const revealed = useRef<string | null>(null)
  useLayoutEffect(() => {
    const row = selectedRow.current
    if (!reveal || !row || revealed.current === reveal.key) return
    revealed.current = reveal.key
    // Clear the sticky summary bar when scrolling up to the row (a computed value, hence inline).
    row.style.scrollMarginTop = `${(summaryBar.current?.offsetHeight ?? 0) + 8}px`
    // Instant and minimal: the document text runs its own smooth scroll at the same moment.
    row.scrollIntoView({ block: 'nearest' })
    if (reveal.how === 'focus') row.focus({ preventScroll: true })
  }, [reveal])

  const clear = useCallback(() => {
    // Focus inside the closing inspector would fall to the page: keep it on the row.
    const row = selectedRow.current
    const active = document.activeElement
    if (row && active && active !== row && row.closest('li')?.contains(active)) row.focus({ preventScroll: true })
    select(null)
  }, [select])
  useFieldKeyboard({ order, selected: selectedFieldId, select: (id) => select(id, { reveal: 'focus' }), clear })

  const toggle = (key: string) =>
    setActive((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  const single = cases.length === 1 ? kase.label : undefined
  return (
    <div className={styles.panel}>
      <div className={styles.header}>
        <h2 className={styles.heading}>Extracted fields</h2>
        <span className={styles.caseLabel}>{cases.length > 1 ? `${cases.length} cases` : single}</span>
      </div>
      <CaseTabs cases={cases} value={caseIndex} onChange={setCase} riskCounts={riskCounts}>
        <SummaryBar
          barRef={summaryBar}
          chips={chips}
          active={active}
          onToggle={toggle}
          riskyOnly={riskyOnly}
          riskyCount={allRows.filter((r) => r.risky).length}
          onRiskyOnly={() => setRiskyOnly((v) => !v)}
          shown={shown}
          total={allRows.length}
          onClear={() => {
            setActive(new Set())
            setRiskyOnly(false)
          }}
        />
        {shown === 0 && <p className={styles.unavailable}>No fields match these filters.</p>}
        {sections.map((section) => {
          const rows = section.rows.filter(visible)
          // Sections with no visible row are hidden entirely; the summary bar says how many are shown.
          if (rows.length === 0) return null
          return (
            <SectionGroup key={section.id} label={section.label} shown={rows.length} total={section.rows.length}>
              {rows.map((row) => {
                const id = row.field.id
                const selected = id === selectedFieldId
                const invalid = invalidByField.get(id)
                return (
                  <FieldRow
                    key={id}
                    row={row}
                    selected={selected}
                    buttonRef={selected ? selectedRow : undefined}
                    quoteOutside={invalid !== undefined}
                    onToggle={() => select(selected ? null : id)}
                    inspector={(inspectorId) => (
                      <FieldInspector
                        // Keyed by draft: a case switch starts the control afresh (no half-typed text carried over).
                        key={draftKey(doc.id, shownIndex, id)}
                        id={inspectorId}
                        field={row.field}
                        result={row.result}
                        text={doc.text}
                        invalid={invalid ?? []}
                        draftKey={draftKey(doc.id, shownIndex, id)}
                        modelText={(row.model?.value ?? row.value).text}
                      />
                    )}
                  />
                )
              })}
            </SectionGroup>
          )
        })}
        {model.uncatalogued.length > 0 && (
          <p role="note" className={styles.warning}>
            <span aria-hidden="true">⚠ </span>
            {model.uncatalogued.length} value{model.uncatalogued.length === 1 ? '' : 's'} in this output{' '}
            {model.uncatalogued.length === 1 ? 'is' : 'are'} not in the catalog and not shown.
          </p>
        )}
      </CaseTabs>
    </div>
  )
}
