import type { CatalogField } from '../api/types'
import { NONE_STATED } from './placeholders'

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const INVALID = 'Invalid value'

function invalid(field: CatalogField, value: unknown): string {
  console.error(`formatValue: invalid value for ${field.type} field`, value)
  return INVALID
}

function label(field: CatalogField, code: string): string {
  const opt = field.options?.find((o) => o.code === code)
  if (opt) return opt.label
  console.error(`formatValue: unknown option code "${code}"`)
  return `Unknown option "${code}"`
}

/** Format a date string YYYY-MM-DD as "D MMM YYYY" by manual parsing (no Date, no timezone). */
function formatDate(s: string): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
  if (!m) return null
  const y = Number(m[1]), mo = Number(m[2]), d = Number(m[3])
  const days = [31, (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0 ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  if (mo < 1 || mo > 12 || d < 1 || d > (days[mo - 1] ?? 0)) return null
  return `${d} ${MONTHS[mo - 1] as string} ${y}`
}

const isStrings = (v: unknown): v is string[] => Array.isArray(v) && v.every((x) => typeof x === 'string')

/** Row display text for a field value. Pure. */
export function formatValue(field: CatalogField, value: unknown): string {
  switch (field.type) {
    case 'string':
      return typeof value === 'string' ? value : invalid(field, value)
    case 'integer':
    case 'number':
      return typeof value === 'number' && Number.isFinite(value) ? String(value) : invalid(field, value)
    case 'date': {
      const out = typeof value === 'string' ? formatDate(value) : null
      return out ?? invalid(field, value)
    }
    case 'coded':
      return typeof value === 'string' ? label(field, value) : invalid(field, value)
    case 'coded_multi':
      return isStrings(value) ? value.map((c) => label(field, c)).join(', ') : invalid(field, value)
    case 'string_list':
      if (!isStrings(value)) return invalid(field, value)
      return value.length === 0 ? NONE_STATED : value.join(', ')
  }
}

/**
 * Per-item display text of a list-typed value, for chips: option labels for coded_multi, the items
 * for string_list. null for other types and for values that are not lists of strings.
 */
export function listItems(field: CatalogField, value: unknown): string[] | null {
  if (!isStrings(value)) return null
  if (field.type === 'coded_multi') return value.map((c) => label(field, c))
  if (field.type === 'string_list') return value
  return null
}

/** "No case" / "1 case" / "N cases". */
export const caseText = (n: number): string => (n === 0 ? 'No case' : n === 1 ? '1 case' : `${n} cases`)
