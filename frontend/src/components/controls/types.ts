import type { CatalogField } from '../../api/types'

/** What every control receives. The field's catalog label names the control (`id` is its labelled element). */
export interface ControlProps<T> {
  id: string
  field: CatalogField
  value: T
  onChange: (value: T) => void
}

/** The id of a control's inline validity message, for aria-describedby. */
export const errorIdOf = (id: string): string => `${id}-error`
