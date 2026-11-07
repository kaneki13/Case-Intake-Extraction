import { useEffect, type ReactNode } from 'react'
import type { CatalogField, FieldType } from '../../api/types'
import type { EditValue } from '../../lib/values'
import { CodedMultiSelect } from './CodedMultiSelect'
import { CodedSelect } from './CodedSelect'
import { DateControl } from './DateControl'
import { NumberControl } from './NumberControl'
import { StringListControl } from './StringListControl'
import { TextControl } from './TextControl'
import type { EditStart } from './types'
import styles from './Control.module.css'

interface Props {
  /** DOM id of the labelled control element. */
  id: string
  field: CatalogField
  /** The value being edited (starts as the current value); null or undefined when there is no value. */
  value: unknown
  onChange: (value: EditValue) => void
  /** Start an edit from the keyboard (see `keyStart`). */
  start?: EditStart | null
}

/** How a character typed on a selected row starts the edit of its field. */
export interface KeyStart {
  /** Handed to the control (`EditStart.seed`): null opens the control without it. */
  seed: string | null
  /** The key goes on to the focused control, which the browser types it into. */
  typeThrough: boolean
}

/**
 * How a typed character starts an edit, by catalog type:
 * - string, integer, number: the character replaces the value;
 * - date: a digit is typed into the date input; anything else just opens it;
 * - coded: the list opens with the first option starting with the character highlighted;
 * - coded_multi, string_list: the character goes into the filter or add box.
 */
export function keyStart(type: FieldType, char: string): KeyStart {
  switch (type) {
    case 'string':
    case 'integer':
    case 'number':
    case 'coded':
    case 'coded_multi':
    case 'string_list':
      return { seed: char, typeThrough: false }
    case 'date':
      return { seed: null, typeThrough: /^\d$/.test(char) }
    default: {
      // Compile time: every catalog type has a case. Run time: a newer catalog may send another.
      const unexpected: never = type
      console.error(`keyStart: unsupported field type "${String(unexpected)}"`)
      return { seed: null, typeThrough: false }
    }
  }
}

/** A value the control can show, plus the reason when the incoming value did not fit it. */
interface Fit<T> {
  value: T
  problem: string | null
}

const shown = (v: unknown): string => JSON.stringify(v) ?? String(v)
const misfit = <T,>(empty: T, raw: unknown): Fit<T> => ({
  value: empty,
  problem: `The current value ${shown(raw)} does not fit this control, so the control starts empty.`,
})
const absent = (v: unknown) => v === null || v === undefined

function asText(raw: unknown): Fit<string | null> {
  if (absent(raw)) return { value: null, problem: null }
  return typeof raw === 'string' ? { value: raw, problem: null } : misfit(null, raw)
}
function asNumber(raw: unknown): Fit<number | null> {
  if (absent(raw)) return { value: null, problem: null }
  return typeof raw === 'number' && Number.isFinite(raw) ? { value: raw, problem: null } : misfit(null, raw)
}
function asDate(raw: unknown): Fit<string | null> {
  if (absent(raw)) return { value: null, problem: null }
  return typeof raw === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(raw) ? { value: raw, problem: null } : misfit(null, raw)
}
function asCode(field: CatalogField, raw: unknown): Fit<string | null> {
  if (absent(raw)) return { value: null, problem: null }
  if (typeof raw !== 'string') return misfit(null, raw)
  if (field.options?.some((o) => o.code === raw)) return { value: raw, problem: null }
  return { value: null, problem: `The current value "${raw}" is not a catalog option, so no option is chosen.` }
}
function asStrings(raw: unknown): Fit<string[]> {
  if (absent(raw)) return { value: [], problem: null }
  return Array.isArray(raw) && raw.every((x) => typeof x === 'string') ? { value: raw, problem: null } : misfit([], raw)
}
function asCodes(field: CatalogField, raw: unknown): Fit<string[]> {
  const list = asStrings(raw)
  if (list.problem) return list
  const unknown = list.value.filter((c) => !field.options?.some((o) => o.code === c))
  if (unknown.length === 0) return list
  const one = unknown.length === 1
  return {
    value: list.value.filter((c) => !unknown.includes(c)),
    problem: `${unknown.map((c) => `"${c}"`).join(', ')} ${one ? 'is not a catalog option' : 'are not catalog options'}, so the control leaves ${one ? 'it' : 'them'} out.`,
  }
}

/**
 * The editing control for a catalog field, chosen by its type alone. Every field of a type gets the
 * same control; the catalog supplies the label, options and validation. A type this screen does not
 * know renders an explicit notice. Values that do not fit the control (the row has already reported
 * them) start it empty, with a notice saying so.
 */
export function FieldControl({ id, field, value, onChange, start = null }: Props) {
  const type = field.type
  let control: ReactNode
  let problem: string | null = null
  switch (type) {
    case 'string': {
      const fit = asText(value)
      problem = fit.problem
      control = <TextControl id={id} field={field} value={fit.value} onChange={onChange} start={start} />
      break
    }
    case 'integer':
    case 'number': {
      const fit = asNumber(value)
      problem = fit.problem
      control = <NumberControl id={id} field={field} value={fit.value} onChange={onChange} start={start} />
      break
    }
    case 'date': {
      const fit = asDate(value)
      problem = fit.problem
      control = <DateControl id={id} field={field} value={fit.value} onChange={onChange} start={start} />
      break
    }
    case 'coded': {
      const fit = asCode(field, value)
      problem = fit.problem
      control = <CodedSelect id={id} field={field} value={fit.value} onChange={onChange} start={start} />
      break
    }
    case 'coded_multi': {
      const fit = asCodes(field, value)
      problem = fit.problem
      control = <CodedMultiSelect id={id} field={field} value={fit.value} onChange={onChange} start={start} />
      break
    }
    case 'string_list': {
      const fit = asStrings(value)
      problem = fit.problem
      control = <StringListControl id={id} field={field} value={fit.value} onChange={onChange} start={start} />
      break
    }
    default: {
      // Compile time: every catalog type has a case. Run time: a newer catalog may send another.
      const unexpected: never = type
      return <Unsupported field={field} type={String(unexpected)} />
    }
  }

  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {field.label}
      </label>
      {control}
      {problem && (
        <p role="note" className={styles.notice}>
          <span aria-hidden="true">⚠ </span>
          {problem}
        </p>
      )}
    </div>
  )
}

function Unsupported({ field, type }: { field: CatalogField; type: string }) {
  useEffect(() => {
    console.error(`FieldControl: unsupported field type "${type}" for "${field.label}"`)
  }, [field.label, type])
  return (
    <div className={styles.field}>
      <span className={styles.label}>{field.label}</span>
      <p role="note" className={styles.notice}>
        <span aria-hidden="true">⚠ </span>Unsupported field type {type}. This screen cannot edit it.
      </p>
    </div>
  )
}
