import type { CatalogField, FieldResult } from '../api/types'
import styles from './compare.module.css'

/** How one catalog field compares across two cases. Same states as the backend `ValueComparator`. */
export type Comparison = 'match' | 'differ' | 'one-sided' | 'both-absent'

/** Every state, in the order the UI reports them. */
export const COMPARISONS: readonly Comparison[] = ['match', 'differ', 'one-sided', 'both-absent']

/** Only an extracted value with a value counts as present; unknown and conflict are absent. */
const present = (r: FieldResult | undefined): r is FieldResult & { value: NonNullable<unknown> } =>
  r !== undefined && r.status === 'extracted' && r.value !== undefined && r.value !== null

const fold = (s: string): string => s.trim().toLowerCase()

/** An extracted value that does not have its type's shape: an error, never a guess (as on the server). */
function malformed(field: CatalogField, expected: string): Error {
  return new Error(`compareFieldValues: an extracted value of a ${field.type} field is not ${expected}`)
}

function text(field: CatalogField, v: unknown): string {
  if (typeof v !== 'string') throw malformed(field, 'a string')
  return v
}

function integer(field: CatalogField, v: unknown): number {
  if (typeof v !== 'number' || !Number.isInteger(v)) throw malformed(field, 'an integer')
  return v
}

function number(field: CatalogField, v: unknown): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw malformed(field, 'a number')
  return v
}

function set(field: CatalogField, v: unknown, folded: boolean): Set<string> {
  if (!Array.isArray(v)) throw malformed(field, 'a list')
  return new Set(v.map((item) => (folded ? fold(text(field, item)) : text(field, item))))
}

const sameSet = (x: Set<string>, y: Set<string>): boolean => x.size === y.size && [...x].every((s) => y.has(s))

function equal(field: CatalogField, a: unknown, b: unknown, integerTolerance: number): boolean {
  switch (field.type) {
    case 'string':
      return fold(text(field, a)) === fold(text(field, b))
    case 'integer':
      return Math.abs(integer(field, a) - integer(field, b)) <= integerTolerance
    case 'number':
      return number(field, a) === number(field, b)
    case 'date':
    case 'coded':
      return text(field, a) === text(field, b)
    case 'coded_multi':
      return sameSet(set(field, a, false), set(field, b, false))
    case 'string_list': {
      const x = set(field, a, true)
      const y = set(field, b, true)
      return (x.size === 0 && y.size === 0) || [...x].some((s) => y.has(s))
    }
    default: {
      const never: never = field.type
      throw new Error(`compareFieldValues: unknown field type ${String(never)}`)
    }
  }
}

/**
 * Compare one field of two cases by its catalog type, with the rules of the backend `ValueComparator`
 * (STRETCH §2.2). `integerTolerance` is the server's `duplicates.integer-tolerance`, as GET /api/duplicates
 * sends it; there is no frontend copy. Throws when an extracted value does not have its type's shape.
 */
export function compareFieldValues(
  field: CatalogField,
  a: FieldResult | undefined,
  b: FieldResult | undefined,
  integerTolerance: number,
): Comparison {
  const hasA = present(a)
  const hasB = present(b)
  if (!hasA && !hasB) return 'both-absent'
  if (!hasA || !hasB) return 'one-sided'
  return equal(field, a.value, b.value, integerTolerance) ? 'match' : 'differ'
}

/** The row class of a state: each state has its own tint and marker (compare.module.css). */
export function comparisonClass(state: Comparison): string {
  switch (state) {
    case 'match':
      return styles.match as string
    case 'differ':
      return styles.differ as string
    case 'one-sided':
      return styles.oneSided as string
    case 'both-absent':
      return styles.bothAbsent as string
    default: {
      const never: never = state
      throw new Error(`comparisonClass: unknown state ${String(never)}`)
    }
  }
}
