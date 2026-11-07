import { useEffect, useMemo, useState } from 'react'
import { errorIdOf, type ControlProps } from './types'
import { FieldMessage } from './FieldMessage'
import styles from './Control.module.css'

/** The text's value: trimmed, and null when nothing is left. */
const parse = (text: string): string | null => (text.trim() === '' ? null : text.trim())

/**
 * Compiles the catalog pattern to match the whole value, as the backend does (Java `Pattern.matches()`).
 * An invalid one is reported, never silently ignored.
 */
function compile(pattern: string | undefined): { re: RegExp | null; broken: boolean } {
  if (pattern === undefined) return { re: null, broken: false }
  try {
    return { re: new RegExp(`^(?:${pattern})$`, 'u'), broken: false }
  } catch {
    return { re: null, broken: true }
  }
}

/**
 * A text input. A catalog `pattern` is checked as the reviewer types: text that does not match shows
 * the pattern inline and is not kept in the draft until it matches.
 */
export function TextControl({ id, field, value, onChange }: ControlProps<string | null>) {
  const pattern = field.validation?.pattern
  const { re, broken } = useMemo(() => compile(pattern), [pattern])
  const [text, setText] = useState(value ?? '')
  // Follow outside changes (reset to the model value) without clobbering what is being typed.
  const [shown, setShown] = useState(value)
  if (value !== shown) {
    setShown(value)
    if (parse(text) !== value) setText(value ?? '')
  }

  useEffect(() => {
    if (broken) console.error(`TextControl: catalog pattern for "${field.label}" is not a valid regular expression`, pattern)
  }, [broken, field.label, pattern])

  const parsed = parse(text)
  const invalid = re !== null && parsed !== null && !re.test(parsed)
  const errorId = errorIdOf(id)

  return (
    <>
      <input
        id={id}
        type="text"
        className={styles.input}
        value={text}
        autoComplete="off"
        spellCheck={false}
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? errorId : undefined}
        onChange={(e) => {
          const next = e.target.value
          setText(next)
          const v = parse(next)
          if (re === null || v === null || re.test(v)) onChange(v)
        }}
      />
      {invalid && (
        <FieldMessage id={errorId}>{`Does not match the catalog format ${pattern ?? ''}. Not kept until it matches.`}</FieldMessage>
      )}
      {broken && (
        <p role="note" className={styles.notice}>
          <span aria-hidden="true">⚠ </span>The catalog format for this field is not valid, so values are not checked.
        </p>
      )}
    </>
  )
}
