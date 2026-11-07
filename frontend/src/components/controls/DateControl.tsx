import { useState } from 'react'
import { errorIdOf, type ControlProps } from './types'
import { FieldMessage } from './FieldMessage'
import styles from './Control.module.css'

/** A native date input; the value is the ISO date (YYYY-MM-DD) the input reports, null when cleared. */
export function DateControl({ id, value, onChange }: ControlProps<string | null>) {
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

  const errorId = errorIdOf(id)
  return (
    <>
      <input
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
