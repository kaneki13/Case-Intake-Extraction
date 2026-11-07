import type { Catalog, CaseOutput } from '../api/types'
import { buildCodePointIndex, toUtf16 } from './offsets'
import { buildSegments, type Segment, type SpanInput } from './segments'

export interface InvalidSpan { fieldId: string; start: number; end: number }
export interface QuoteModel {
  /** Contiguous segments covering the whole text (UTF-16 offsets). */
  segments: Segment[]
  /** Provenance spans skipped because they do not lie inside the document. */
  invalid: InvalidSpan[]
}

/**
 * Turn every provenance span of one case into segments. Spans arrive in code points and are
 * converted to UTF-16 here; invalid ones (start >= end, or beyond the text) are skipped and reported.
 * Catalog order drives the field order inside a segment.
 */
export function buildQuoteModel(text: string, catalog: Catalog, kase: CaseOutput | undefined): QuoteModel {
  const index = buildCodePointIndex(text)
  const count = index.length - 1
  const spans: SpanInput[] = []
  const invalid: InvalidSpan[] = []
  for (const field of catalog.fields) {
    for (const p of kase?.fields[field.id]?.provenance ?? []) {
      if (!Number.isInteger(p.start) || !Number.isInteger(p.end) || p.start < 0 || p.start >= p.end || p.end > count) {
        invalid.push({ fieldId: field.id, start: p.start, end: p.end })
        continue
      }
      spans.push({ fieldId: field.id, from: toUtf16(index, p.start), to: toUtf16(index, p.end) })
    }
  }
  return { segments: buildSegments(text.length, spans), invalid }
}
