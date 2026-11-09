import { useLayoutEffect, useRef, useState } from 'react'
import * as Select from '@radix-ui/react-select'
import { Icon } from '../Icon'
import type { ControlProps } from './types'
import styles from './Control.module.css'

/**
 * A single choice among the catalog's options: the items show option labels, the value is the code.
 * A start from Enter focuses the trigger. A start with a seed opens the list with the first option
 * whose label starts with the seed highlighted (type-ahead); nothing is chosen until Enter.
 */
export function CodedSelect({ id, field, value, onChange, start }: ControlProps<string | null>) {
  const options = field.options ?? []
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const items = useRef(new Map<string, HTMLElement>())
  // The option to highlight once the list has opened and placed its own focus.
  const typeahead = useRef<string | null>(null)

  useLayoutEffect(() => {
    if (!start) return
    if (start.seed === null) {
      triggerRef.current?.focus()
      return
    }
    const seed = start.seed.toLowerCase()
    typeahead.current = options.find((o) => o.label.toLowerCase().startsWith(seed))?.code ?? null
    setOpen(true)
    // Only a new start request acts; the options are read as they are then.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start])

  return (
    // An empty string shows the placeholder (no choice yet).
    <Select.Root value={value ?? ''} onValueChange={(code) => onChange(code)} 
      open={open}
      onOpenChange={(next) => {
        if (!next) typeahead.current = null
        setOpen(next)
      }}
    >
      <Select.Trigger ref={triggerRef} id={id} className={styles.trigger}>
        <Select.Value placeholder="Choose…" />
        <Select.Icon className={styles.chevron}>
          <Icon name="chevronDown" size={14} />
        </Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Content
          className={styles.menu}
          position="popper"
          sideOffset={4}
          align="start"
          onFocusCapture={() => {
            // The list focuses the chosen (or first) option when it opens: move on to the type-ahead match.
            const code = typeahead.current
            if (code === null) return
            typeahead.current = null
            const item = items.current.get(code)
            if (item && item !== document.activeElement) item.focus()
          }}
        >
          <Select.Viewport className={styles.viewport}>
            {options.map((o) => (
              <Select.Item
                key={o.code}
                ref={(node) => {
                  if (node) items.current.set(o.code, node)
                  else items.current.delete(o.code)
                }}
                value={o.code}
                className={styles.item}
              >
                <Select.ItemText>{o.label}</Select.ItemText>
                <Select.ItemIndicator className={styles.itemCheck}>
                  <Icon name="check" size={14} />
                </Select.ItemIndicator>
              </Select.Item>
            ))}
          </Select.Viewport>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  )
}
