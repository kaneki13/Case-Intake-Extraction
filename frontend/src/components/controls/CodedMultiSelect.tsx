import { useLayoutEffect, useRef, useState } from 'react'
import * as Popover from '@radix-ui/react-popover'
import { Icon } from '../Icon'
import type { ControlProps } from './types'
import styles from './Control.module.css'

/**
 * Several of the catalog's options: a popover with a filter box and one checkbox per option, and the
 * chosen options as chips with remove buttons. The value lists codes in catalog order. A start from
 * Enter focuses the trigger; a start with a seed opens the popover with the seed in the filter box.
 * Enter in the filter box toggles the first option it shows.
 */
export function CodedMultiSelect({ id, field, value, onChange, start }: ControlProps<string[]>) {
  const options = field.options ?? []
  const triggerRef = useRef<HTMLButtonElement>(null)
  const filterRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const chosen = new Set(value)
  const labelOf = (code: string) => options.find((o) => o.code === code)?.label ?? code
  const commit = (next: Set<string>) => onChange(options.filter((o) => next.has(o.code)).map((o) => o.code))
  const toggle = (code: string) => {
    const next = new Set(chosen)
    if (next.has(code)) next.delete(code)
    else next.add(code)
    commit(next)
  }
  const needle = query.trim().toLowerCase()
  const shown = needle === '' ? options : options.filter((o) => o.label.toLowerCase().includes(needle))

  useLayoutEffect(() => {
    if (!start) return
    if (start.seed === null) {
      triggerRef.current?.focus()
      return
    }
    setQuery(start.seed)
    setOpen(true)
  }, [start])

  return (
    <>
      <Popover.Root
        open={open}
        onOpenChange={(next) => {
          setOpen(next)
          if (!next) setQuery('')
        }}
      >
        <Popover.Trigger asChild>
          <button ref={triggerRef} id={id} type="button" className={styles.trigger}>
            <span>{value.length === 0 ? 'Choose…' : `${value.length} selected`}</span>
            <span className={styles.chevron}>
              <Icon name="chevronDown" size={14} />
            </span>
          </button>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            className={styles.menu}
            aria-label={field.label}
            side="bottom"
            align="start"
            sideOffset={4}
            onOpenAutoFocus={(event) => {
              // A filter typed on the row keeps focus in the filter box; otherwise the first option takes it.
              event.preventDefault()
              const filter = filterRef.current
              if (filter && filter.value !== '') {
                filter.focus()
                filter.setSelectionRange(filter.value.length, filter.value.length)
              } else {
                listRef.current?.querySelector<HTMLElement>('input')?.focus()
              }
            }}
          >
            <input
              ref={filterRef}
              type="search"
              className={`${styles.input} ${styles.filter}`}
              aria-label="Filter options"
              placeholder="Filter"
              autoComplete="off"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key !== 'Enter') return
                e.preventDefault()
                const first = shown[0]
                if (first) toggle(first.code)
              }}
            />
            {shown.length === 0 ? (
              <p className={styles.none}>No option matches.</p>
            ) : (
              <ul ref={listRef} className={styles.checks}>
                {shown.map((o) => (
                  <li key={o.code}>
                    <label className={styles.check}>
                      <input type="checkbox" checked={chosen.has(o.code)} onChange={() => toggle(o.code)} />
                      <span>{o.label}</span>
                    </label>
                  </li>
                ))}
              </ul>
            )}
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
