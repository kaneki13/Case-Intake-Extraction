import { useLayoutEffect, useRef, useState } from 'react'
import { NONE_STATED } from '../../lib/placeholders'
import { Icon } from '../Icon'
import { errorIdOf, type ControlProps } from './types'
import { FieldMessage } from './FieldMessage'
import styles from './Control.module.css'

/**
 * A list of free-text items: chips with remove buttons, and an input that adds a trimmed, new item.
 * A start focuses the add input; a seed goes into it.
 */
export function StringListControl({ id, field, value, onChange, start }: ControlProps<string[]>) {
  const [entry, setEntry] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const errorId = errorIdOf(id)

  useLayoutEffect(() => {
    if (!start) return
    inputRef.current?.focus()
    if (start.seed === null) return
    setEntry(start.seed)
    setMessage(null)
  }, [start])

  const add = () => {
    const item = entry.trim()
    if (item === '') {
      setEntry('')
      return
    }
    if (value.some((v) => v.toLowerCase() === item.toLowerCase())) {
      setMessage(`"${item}" is already in the list.`)
      return
    }
    onChange([...value, item])
    setEntry('')
    setMessage(null)
  }

  return (
    <>
      {value.length === 0 ? (
        <p className={styles.none}>{NONE_STATED}</p>
      ) : (
        <ul className={styles.chips} aria-label={`${field.label}: items`}>
          {value.map((item, i) => (
            <li key={`${i}-${item}`} className={styles.chip}>
              <span>{item}</span>
              <button
                type="button"
                className={styles.chipRemove}
                aria-label={`Remove ${item}`}
                onClick={() => {
                  onChange(value.filter((_, j) => j !== i))
                  // The chip and its button go away: keep focus in the inspector, on the input.
                  inputRef.current?.focus()
                }}
              >
                <Icon name="x" size={12} />
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className={styles.addRow}>
        <input
          ref={inputRef}
          id={id}
          type="text"
          className={styles.input}
          value={entry}
          autoComplete="off"
          placeholder="Add an item"
          aria-invalid={message !== null || undefined}
          aria-describedby={message !== null ? errorId : undefined}
          onChange={(e) => {
            setEntry(e.target.value)
            setMessage(null)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              add()
            }
          }}
        />
        <button type="button" className={styles.button} onClick={add}>
          Add
        </button>
      </div>
      {message !== null && <FieldMessage id={errorId}>{message}</FieldMessage>}
    </>
  )
}
