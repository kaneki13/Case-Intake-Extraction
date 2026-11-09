import { useLayoutEffect, useRef, useState } from 'react'
import { errorIdOf, type ControlProps } from './types'
import { FieldMessage } from './FieldMessage'
import styles from './Control.module.css'

/**
 * A native date input; the value is the ISO date (YYYY-MM-DD) the input reports, null when cleared.
 * A start only focuses it: a digit typed on the row goes on to the input, which the browser types into.
 */
export function DateControl({ id, value, onChange, start }: ControlProps<string | null>) {
  const [text, setText] = useState(value ?? '')
  // A browser reports a partly typed date as an empty value with badInput set: not a cleared value.
  const [incomplete, setIncomplete] = useState(false)
  // Follow outside changes (reset to the model value) without clobbering what is being typed.
  const [shown, setShown] = useState(value)
  if (value !== shown) {
    setShown(value)
    if ((text === '' ? null : text) !== value || incomplete) {
      setText(value ?? '')
      setIncomplete(false)
    }
  }

  const inputRef = useRef<HTMLInputElement>(null)
  useLayoutEffect(() => {
    if (start) inputRef.current?.focus()
  }, [start])

  const errorId = errorIdOf(id)
  return (
    <>
      <input
        ref={inputRef}
        id={id}
        type="date"
        className={styles.input}
        value={text}
        aria-invalid={incomplete || undefined}
        aria-describedby={incomplete ? errorId : undefined}
        onChange={(e) => {
          const next = e.target.value
          const bad = e.target.validity.badInput
          setText(next)
          setIncomplete(bad)
          if (!bad) onChange(next === '' ? null : next)
        }}
      />
      {incomplete && <FieldMessage id={errorId}>Enter a complete date. Not kept until it is complete.</FieldMessage>}
    </>
  )
}
