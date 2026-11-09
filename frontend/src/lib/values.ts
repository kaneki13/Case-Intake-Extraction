import type { CatalogField } from '../api/types'

/**
 * A value as a control holds it while the reviewer edits. The shape follows the catalog type: text
 * for string and date (ISO), a number for integer and number, a code for coded, codes or strings for
 * the list types. null is an explicitly emptied value.
 */
export type EditValue = string | number | string[] | null

const isEmpty = (v: unknown): boolean => v === null || v === undefined || (Array.isArray(v) && v.length === 0)

/**
 * Whether two values of `field` are the same, so saving one over the other changes nothing.
 * Empty equals empty (an unknown field has no value; a cleared list is empty). Coded multi-selections
 * compare as sets.
 */
export function sameValue(field: CatalogField, a: unknown, b: unknown): boolean {
  if (isEmpty(a) || isEmpty(b)) return isEmpty(a) && isEmpty(b)
  const norm = (v: unknown) => (field.type === 'coded_multi' && Array.isArray(v) ? [...(v as unknown[])].map(String).sort() : v)
  return JSON.stringify(norm(a)) === JSON.stringify(norm(b))
}
