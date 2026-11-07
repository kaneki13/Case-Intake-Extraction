import { useRef } from 'react'
import * as Popover from '@radix-ui/react-popover'
import { Icon } from '../Icon'
import type { ControlProps } from './types'
import styles from './Control.module.css'

/**
 * Several of the catalog's options: a popover with one checkbox per option, and the chosen options as
 * chips with remove buttons. The value lists codes in catalog order.
 */
export function CodedMultiSelect({ id, field, value, onChange }: ControlProps<string[]>) {
  const options = field.options ?? []
  const triggerRef = useRef<HTMLButtonElement>(null)
  const chosen = new Set(value)
  const labelOf = (code: string) => options.find((o) => o.code === code)?.label ?? code
  const commit = (next: Set<string>) => onChange(options.filter((o) => next.has(o.code)).map((o) => o.code))
  const toggle = (code: string) => {
    const next = new Set(chosen)
    if (next.has(code)) next.delete(code)
    else next.add(code)
    commit(next)
  }

  return (
    <>
      <Popover.Root>
        <Popover.Trigger asChild>
          <button ref={triggerRef} id={id} type="button" className={styles.trigger}>
            <span>{value.length === 0 ? 'Choose…' : `${value.length} selected`}</span>
            <span className={styles.chevron}>
              <Icon name="chevronDown" size={14} />
            </span>
          </button>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content className={styles.menu} aria-label={field.label} side="bottom" align="start" sideOffset={4}>
            <ul className={styles.checks}>
              {options.map((o) => (
                <li key={o.code}>
                  <label className={styles.check}>
                    <input type="checkbox" checked={chosen.has(o.code)} onChange={() => toggle(o.code)} />
                    <span>{o.label}</span>
                  </label>
                </li>
              ))}
            </ul>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      {value.length === 0 ? (
        <p className={styles.none}>None selected</p>
      ) : (
        <ul className={styles.chips} aria-label={`${field.label}: selected`}>
          {value.map((code) => (
            <li key={code} className={styles.chip}>
              <span>{labelOf(code)}</span>
              <button
                type="button"
                className={styles.chipRemove}
                aria-label={`Remove ${labelOf(code)}`}
                onClick={() => {
                  const next = new Set(chosen)
                  next.delete(code)
                  commit(next)
                  // The chip and its button go away: keep focus in the inspector, on the control.
                  triggerRef.current?.focus()
                }}
              >
                <Icon name="x" size={12} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
