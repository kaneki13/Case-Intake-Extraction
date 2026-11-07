import { useLayoutEffect, useRef, useState } from 'react'
import type { CatalogField } from '../../api/types'
import { errorIdOf, type ControlProps } from './types'
import { FieldMessage } from './FieldMessage'
import styles from './Control.module.css'

type Parsed = { ok: true; value: number | null } | { ok: false; message: string }

/** Reads the input against the field's type and the catalog's min/max. Empty is a valid (cleared) value. */
function parse(field: CatalogField, text: string, badInput: boolean): Parsed {
  if (text.trim() === '') return badInput ? { ok: false, message: 'Enter a number.' } : { ok: true, value: null }
  const n = Number(text)
  const { min, max } = field.validation ?? {}
  if (!Number.isFinite(n)) return { ok: false, message: 'Enter a number.' }
  if (field.type === 'integer' && !Number.isInteger(n)) return { ok: false, message: 'Must be a whole number.' }
  if (min !== undefined && n < min) return { ok: false, message: `Must be at least ${min}.` }
  if (max !== undefined && n > max) return { ok: false, message: `Must be at most ${max}.` }
  return { ok: true, value: n }
}

/**
 * A number input with the catalog's min/max; step 1 for integer fields, any for number fields.
 * Input that is not a number, not whole for an integer, or out of range shows a message inline and is
 * never committed to the draft. Started with a seed, the seed replaces the text.
 */
export function NumberControl({ id, field, value, onChange, start }: ControlProps<number | null>) {
  const [text, setText] = useState(value === null ? '' : String(value))
  const [badInput, setBadInput] = useState(false)
  // Follow outside changes (reset to the model value) without clobbering what is being typed.
  const [shown, setShown] = useState(value)
  if (value !== shown) {
    setShown(value)
    const now = parse(field, text, badInput)
    if (!now.ok || now.value !== value) {
      setText(value === null ? '' : String(value))
      setBadInput(false)
    }
  }

  const inputRef = useRef<HTMLInputElement>(null)
  useLayoutEffect(() => {
    if (!start) return
    inputRef.current?.focus()
    if (start.seed === null) return
    setText(start.seed)
    setBadInput(false)
    const p = parse(field, start.seed, false)
    if (p.ok) onChange(p.value)
    // Only a new start request acts; the other values are read as they are then.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start])

  const parsed = parse(field, text, badInput)
  const errorId = errorIdOf(id)
  const { min, max } = field.validation ?? {}

  return (
    <>
      <input
        ref={inputRef}
        id={id}
        type="number"
        inputMode={field.type === 'integer' ? 'numeric' : 'decimal'}
        className={styles.input}
        value={text}
        min={min}
        max={max}
        step={field.type === 'integer' ? 1 : 'any'}
        aria-invalid={!parsed.ok || undefined}
        aria-describedby={parsed.ok ? undefined : errorId}
        onChange={(e) => {
          const next = e.target.value
          // A browser reports text it cannot read as a number as an empty value with badInput set.
          const bad = e.target.validity.badInput
          setText(next)
          setBadInput(bad)
          const p = parse(field, next, bad)
          if (p.ok) onChange(p.value)
        }}
      />
      {!parsed.ok && <FieldMessage id={errorId}>{`${parsed.message} Not kept until it is valid.`}</FieldMessage>}
    </>
  )
}
