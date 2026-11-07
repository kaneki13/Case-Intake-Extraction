import type { CaseOutput, Catalog, CatalogField, FieldResult } from '../api/types'
import type { DraftValue } from './drafts'
import { formatValue, listItems } from './format'
import { NONE_STATED } from './placeholders'
import { isRisky } from './risk'
import { editedState, resultProblems, stateOf, type FieldState } from './status'

/** What a row shows in its value column. */
export type RowValue =
  /** `items` is set for list types (coded_multi labels, string_list items): the row shows them as chips. */
  | { kind: 'value'; text: string; title: string; items?: string[] }
  /** `empty`: a draft the reviewer cleared. */
  | { kind: 'unknown' | 'conflict' | 'missing' | 'empty'; text: string }

export interface FieldRowModel {
  field: CatalogField
  /** The model's result; undefined when the catalog field is absent from this output. */
  result: FieldResult | undefined
  state: FieldState
  risky: boolean
  value: RowValue
  /** Set when a local draft overrides the row: the model's own state and value, kept visible beside the draft. */
  model?: { state: FieldState; value: RowValue }
}

export interface SectionModel {
  id: string
  label: string
  rows: FieldRowModel[]
}

export interface CaseRows {
  /** Sections in catalog order, each with its fields in catalog order; empty sections are left out. */
  sections: SectionModel[]
  /** Output values the catalog does not define; they have no row. */
  uncatalogued: string[]
  /** Unexpected data, for one console.error per case. */
  problems: string[]
}

export const NOT_STATED = 'Not stated'
export const NOT_IN_OUTPUT = 'Not in this output'
export const NO_VALUE = 'No value'
const OTHER_SECTION = 'Other fields'

function valueOf(field: CatalogField, result: FieldResult | undefined): RowValue {
  if (result === undefined) return { kind: 'missing', text: NOT_IN_OUTPUT }
  if (result.status === 'conflict') return { kind: 'conflict', text: 'Conflict' }
  if (result.status !== 'extracted') return { kind: 'unknown', text: NOT_STATED }
  return displayOf(field, result.value)
}

/** The display of a value of the field's type (a model value or a draft). */
function displayOf(field: CatalogField, value: unknown): RowValue {
  const items = listItems(field, value)
  if (items !== null) {
    const text = items.length === 0 ? NONE_STATED : items.join(', ')
    return items.length === 0 ? { kind: 'value', text, title: text } : { kind: 'value', text, title: text, items }
  }
  const text = formatValue(field, value)
  // Dates show "D MMM YYYY" with the ISO value in the title; everything else repeats the text for truncation.
  const title = field.type === 'date' && typeof value === 'string' ? value : text
  return { kind: 'value', text, title }
}

/** The row with a local draft in place of the model value: edited state, draft value, model kept beside it. */
export function withDraft(row: FieldRowModel, draft: DraftValue): FieldRowModel {
  const value: RowValue = draft === null ? { kind: 'empty', text: NO_VALUE } : displayOf(row.field, draft)
  return { ...row, state: editedState(), value, model: { state: row.state, value: row.value } }
}

/**
 * The rows of one case, driven entirely by the catalog: sections, order and labels.
 * Pure apart from formatValue's console.error on invalid values; call it once per case (memoized).
 */
export function buildCaseRows(catalog: Catalog, kase: CaseOutput): CaseRows {
  const levels = catalog.confidence_levels
  const problems: string[] = []
  const rowOf = (field: CatalogField): FieldRowModel => {
    const result = kase.fields[field.id]
    if (result) for (const p of resultProblems(result, levels)) problems.push(`${field.label}: ${p}`)
    return {
      field,
      result,
      state: stateOf(result, levels),
      risky: result !== undefined && isRisky(result, levels),
      value: valueOf(field, result),
    }
  }

  const sections: SectionModel[] = catalog.sections.map((s) => ({
    id: s.id,
    label: s.label,
    rows: catalog.fields.filter((f) => f.section === s.id).map(rowOf),
  }))
  const known = new Set(catalog.sections.map((s) => s.id))
  const orphans = catalog.fields.filter((f) => !known.has(f.section))
  if (orphans.length > 0) {
    problems.push(`${orphans.length} catalog field(s) name a section the catalog does not define; shown under "${OTHER_SECTION}"`)
    sections.push({ id: '', label: OTHER_SECTION, rows: orphans.map(rowOf) })
  }

  const catalogued = new Set(catalog.fields.map((f) => f.id))
  const uncatalogued = Object.keys(kase.fields).filter((id) => !catalogued.has(id))
  if (uncatalogued.length > 0) problems.push(`${uncatalogued.length} output value(s) are not catalog fields and have no row`)

  return { sections: sections.filter((s) => s.rows.length > 0), uncatalogued, problems }
}
