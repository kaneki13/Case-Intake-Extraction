import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { errorMessage } from '../api/client'
import { useCatalog, useDocument } from '../api/queries'
import { useBulkReview, useReviewField } from '../api/reviews'
import type { BulkAction, CaseOutput, Catalog, DocumentDetail } from '../api/types'
import { useFieldKeyboard, type ReviewKey } from '../hooks/useFieldKeyboard'
import { useQuoteModel } from '../hooks/useQuoteModel'
import { useSelection } from '../hooks/useSelection'
import { buildCaseRows, withReview, type FieldRowModel, type SectionModel } from '../lib/fieldRows'
import { isRisky } from '../lib/risk'
import { REVIEW_KINDS, conflictState, levelState, reviewChipState, unknownState } from '../lib/status'
import type { EditValue } from '../lib/values'
import { BulkReviewBar } from './BulkReviewBar'
import { CaseTabs } from './CaseTabs'
import { ConfirmDialog } from './ConfirmDialog'
import { keyStart } from './controls/FieldControl'
import type { EditStart } from './controls/types'
import { FieldInspector } from './FieldInspector'
import { FieldRow } from './FieldRow'
import { ProgressBar } from './ProgressBar'
import { ReviewButtons, type FieldAction } from './ReviewButtons'
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
  // The server's reviews of this case (only reviewed fields appear).
  const caseReviews = doc.reviews[String(shownIndex)]

  const model = useMemo(() => buildCaseRows(catalog, kase), [catalog, kase])
  // The rows as shown: each with its stored review (accepted, rejected, edited) or none.
  const sections = useMemo<SectionModel[]>(
    () => model.sections.map((s) => ({ ...s, rows: s.rows.map((r) => withReview(r, caseReviews?.[r.field.id])) })),
    [model, caseReviews],
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
    // A level the catalog does not list still gets a chip (risk-toned) rather than vanishing from the counts.
    const seen = new Set(base.map((s) => s.key))
    for (const r of allRows) {
      if (!seen.has(r.state.key)) {
        seen.add(r.state.key)
        chips.push({ state: r.state, count: count(r.state.key) })
      }
    }
    return chips
  }, [allRows, levels])
  const reviewChips = useMemo<SummaryChip[]>(
    () => REVIEW_KINDS.map((kind) => ({ state: reviewChipState(kind), count: allRows.filter((r) => r.reviewKind === kind).length })),
    [allRows],
  )

  // Model-state chips combine with "or", review chips with "or", and the two groups with "and".
  const activeModel = [...active].filter((k) => !k.startsWith('review:'))
  const activeReview = [...active].filter((k) => k.startsWith('review:'))
  const matches = (r: FieldRowModel) =>
    (activeModel.length === 0 || active.has(r.state.key)) &&
    (activeReview.length === 0 || active.has(reviewChipState(r.reviewKind).key)) &&
    (!riskyOnly || r.risky)
  // The selected row stays visible under any filter: the reviewer chose it (e.g. by clicking a quote).
  const visible = (r: FieldRowModel) => matches(r) || r.field.id === selectedFieldId
  // The keyboard's path: visible rows of this case, in display order.
  const order = allRows.filter(visible).map((r) => r.field.id)
  const shown = order.length
  // Bulk actions: under any filter, the shown unreviewed fields only (sent as fieldIds); otherwise the whole case.
  const filtered = active.size > 0 || riskyOnly
  const bulkTargets = allRows.filter((r) => r.reviewKind === 'unreviewed' && (!filtered || visible(r))).map((r) => r.field.id)
  const unreviewed = bulkTargets.length

  // After a review the selection moves on to the next unreviewed visible field (wrapping round once).
  const latest = useRef({ selectedFieldId, allRows, caseIndex: shownIndex })
  useEffect(() => {
    latest.current = { selectedFieldId, allRows, caseIndex: shownIndex }
  })
  const advance = useCallback(
    (from: string, path: readonly string[], fromCase: number) => {
      const now = latest.current
      // The reviewer moved on (another field or case) while the review was saving: leave them there.
      if (now.selectedFieldId !== from || now.caseIndex !== fromCase) return
      const reviewed = new Set(now.allRows.filter((r) => r.reviewKind !== 'unreviewed').map((r) => r.field.id))
      const at = path.indexOf(from)
      const next = [...path.slice(at + 1), ...path.slice(0, Math.max(at, 0))].find((id) => id !== from && !reviewed.has(id))
      if (next !== undefined) select(next, { reveal: 'focus' })
    },
    [select],
  )

  const reviewField = useReviewField(doc.id)
  const bulk = useBulkReview(doc.id)
  const [actionError, setActionError] = useState<{ fieldId: string; message: string } | null>(null)
  const [bulkError, setBulkError] = useState<string | null>(null)
  const [confirmingReject, setConfirmingReject] = useState(false)
  // Reject on an edited field discards the edit: it is asked first (the field id waiting for an answer).
  const [discarding, setDiscarding] = useState<string | null>(null)
  // One review at a time: a field action and a bulk action never overlap.
  const busy = reviewField.isPending || bulk.isPending

  const send = (fieldId: string, action: FieldAction) => {
    if (busy) return
    const path = order
    const fromCase = shownIndex
    setActionError(null)
    reviewField.mutate(
      { caseIndex: shownIndex, fieldId, action },
      {
        onSuccess: () => {
          if (action !== 'reset') advance(fieldId, path, fromCase)
        },
        onError: (error) => setActionError({ fieldId, message: errorMessage(error) }),
      },
    )
  }
  // Accept on an edited field confirms the edit (the server keeps it); reject asks before discarding it.
  const reviewOne = (fieldId: string, action: FieldAction) => {
    if (busy) return
    const row = allRows.find((r) => r.field.id === fieldId)
    if (action === 'reject' && row?.reviewKind === 'edited') setDiscarding(fieldId)
    else send(fieldId, action)
  }
  const discardRow = discarding === null ? undefined : allRows.find((r) => r.field.id === discarding)
  const saveEdit = (fieldId: string, value: EditValue) => {
    const path = order
    const fromCase = shownIndex
    setActionError(null)
    return reviewField
      .mutateAsync({ caseIndex: shownIndex, fieldId, action: 'edit', value })
      .then(() => advance(fieldId, path, fromCase))
  }
  const bulkReview = (action: BulkAction) => {
    if (busy) return
    setBulkError(null)
    bulk.mutate(
      filtered ? { caseIndex: shownIndex, action, fieldIds: bulkTargets } : { caseIndex: shownIndex, action },
      { onError: (error) => setBulkError(`Couldn't ${action} all: ${errorMessage(error)}`) },
    )
  }

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
  const focusRow = useCallback(() => selectedRow.current?.focus({ preventScroll: true }), [])

  // A keyboard request to start editing the selected field, handed to its inspector once.
  const [editStart, setEditStart] = useState<{ fieldId: string; start: EditStart } | null>(null)
  // A request never outlives its selection (it must not fire when the field is selected again later).
  if (editStart !== null && editStart.fieldId !== selectedFieldId) setEditStart(null)
  const startEdit = (seed: string | null): boolean => {
    const row = allRows.find((r) => r.field.id === selectedFieldId)
    if (!row) return false
    const how = seed === null ? { seed: null, typeThrough: false } : keyStart(row.field.type, seed)
    // Synchronously, so the control has focus before the browser acts on the key (type-through).
    flushSync(() => setEditStart({ fieldId: row.field.id, start: { seed: how.seed } }))
    return !how.typeThrough
  }
  const reviewAll = (action: ReviewKey) => {
    if (busy || unreviewed === 0) return
    if (action === 'accept') bulkReview('accept')
    else setConfirmingReject(true)
  }
  useFieldKeyboard({
    order,
    selected: selectedFieldId,
    selectedRow,
    select: (id) => select(id, { reveal: 'focus' }),
    clear,
    startEdit,
    review: (action) => {
      if (selectedFieldId !== null) reviewOne(selectedFieldId, action)
    },
    reviewAll,
  })

  const toggle = (key: string) =>
    setActive((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  const single = cases.length === 1 ? kase.label : undefined
  const caseName = cases.length > 1 ? `case ${shownIndex + 1}${kase.label ? ` (${kase.label})` : ''}` : undefined
  return (
    <div className={styles.panel}>
      <div className={styles.header}>
        <div className={styles.titleRow}>
          <h2 className={styles.heading}>Extracted fields</h2>
          <span className={styles.caseLabel}>{cases.length > 1 ? `${cases.length} cases` : single}</span>
        </div>
        <div className={styles.reviewRow}>
          <ProgressBar progress={doc.progress} label="Document review progress" />
          <BulkReviewBar
            unreviewed={unreviewed}
            shownOnly={filtered}
            caseName={caseName}
            onBulk={bulkReview}
            confirming={confirmingReject}
            onConfirming={setConfirmingReject}
            busy={busy}
            error={bulkError}
          />
        </div>
      </div>
      <CaseTabs cases={cases} value={caseIndex} onChange={setCase} riskCounts={riskCounts}>
        <SummaryBar
          barRef={summaryBar}
          chips={chips}
          reviewChips={reviewChips}
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
                    actions={
                      <ReviewButtons
                        variant="compact"
                        label={`Review ${row.field.label}`}
                        review={row.review}
                        busy={busy}
                        onAction={(action) => reviewOne(id, action)}
                      />
                    }
                    inspector={(inspectorId) => (
                      <FieldInspector
                        // Keyed by document, case and field: a case switch starts the control afresh.
                        key={`${doc.id}/${shownIndex}/${id}`}
                        id={inspectorId}
                        field={row.field}
                        result={row.result}
                        review={row.review}
                        text={doc.text}
                        invalid={invalid ?? []}
                        modelText={row.value.text}
                        onSave={(value) => saveEdit(id, value)}
                        onReview={(action) => reviewOne(id, action)}
                        busy={busy}
                        actionError={actionError?.fieldId === id ? actionError.message : null}
                        start={editStart?.fieldId === id ? editStart.start : null}
                        onStarted={() => setEditStart(null)}
                        onClose={focusRow}
                      />
                    )}
                  />
                )
              })}
            </SectionGroup>
          )
        })}
        <ConfirmDialog
          open={discardRow !== undefined}
          title="Discard manual edit?"
          confirmText="Discard edit and reject"
          tone="danger"
          onCancel={() => setDiscarding(null)}
          onConfirm={() => {
            const id = discarding
            setDiscarding(null)
            if (id !== null) send(id, 'reject')
          }}
        >
          {discardRow && (
            <p>
              {discardRow.field.label} has a manual edit: “{discardRow.edited?.text ?? ''}”. Rejecting it discards
              the edit and rejects the model value. Reset undoes the rejection, but not the edit.
            </p>
          )}
        </ConfirmDialog>
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
