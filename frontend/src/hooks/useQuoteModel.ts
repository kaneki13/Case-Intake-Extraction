import { useMemo } from 'react'
import { useCatalog, useDocument } from '../api/queries'
import { buildQuoteModel, type InvalidSpan, type QuoteModel } from '../lib/quotes'
import { useSelection } from './useSelection'

export interface UseQuoteModel extends QuoteModel {
  /** Skipped spans per field, for the inspector's "Quote position outside document" notice. */
  invalidByField: Map<string, { start: number; end: number }[]>
  /** `?field=` if it is a catalog field (present in the current case or not), else null. */
  selectedFieldId: string | null
  /** True when `?field=` was given but is not a catalog field (loaded data only). */
  unknownField: boolean
  /** False until document, catalog and text are available. */
  ready: boolean
}

const EMPTY: InvalidSpan[] = []

/** Quote segments and invalid spans for the current document and case, memoized. Pure: it logs nothing. */
export function useQuoteModel(): UseQuoteModel {
  const { docId, caseIndex, fieldId } = useSelection()
  const doc = useDocument(docId).data
  const catalog = useCatalog().data
  const text = doc?.text ?? null
  const kase = doc?.extraction.cases[caseIndex]

  const model = useMemo<QuoteModel | null>(
    () => (text !== null && catalog ? buildQuoteModel(text, catalog, kase) : null),
    [text, catalog, kase],
  )
  const invalid = model?.invalid ?? EMPTY
  const invalidByField = useMemo(() => {
    const m = new Map<string, { start: number; end: number }[]>()
    for (const s of invalid) m.set(s.fieldId, [...(m.get(s.fieldId) ?? []), { start: s.start, end: s.end }])
    return m
  }, [invalid])

  // A catalog field absent from this case is still a row (marked NOT_IN_OUTPUT), so it can be selected.
  const known =
    fieldId !== null && catalog !== undefined && kase !== undefined
      ? catalog.fields.some((f) => f.id === fieldId)
      : null
  return {
    segments: model?.segments ?? [],
    invalid,
    invalidByField,
    selectedFieldId: known ? fieldId : null,
    unknownField: known === false,
    ready: model !== null,
  }
}
