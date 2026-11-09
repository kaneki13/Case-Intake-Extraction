import type { CatalogField } from '../../api/types'

/**
 * A request to start editing from the keyboard: each request is a new object, handled once.
 * `seed` is the character the reviewer typed on the row, or null when they pressed Enter.
 */
export interface EditStart {
  seed: string | null
}

/** What every control receives. The field's catalog label names the control (`id` is its labelled element). */
export interface ControlProps<T> {
  id: string
  field: CatalogField
  value: T
  onChange: (value: T) => void
  /** Start an edit: take focus and apply the seed as the control's type says (FieldControl documents each). */
  start?: EditStart | null
}

/** The id of a control's inline validity message, for aria-describedby. */
export const errorIdOf = (id: string): string => `${id}-error`
