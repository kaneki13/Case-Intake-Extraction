import * as Select from '@radix-ui/react-select'
import { Icon } from '../Icon'
import type { ControlProps } from './types'
import styles from './Control.module.css'

/** A single choice among the catalog's options: the items show option labels, the value is the code. */
export function CodedSelect({ id, field, value, onChange }: ControlProps<string | null>) {
  const options = field.options ?? []
  return (
    // An empty string shows the placeholder (no choice yet).
    <Select.Root value={value ?? ''} onValueChange={(code) => onChange(code)}>
      <Select.Trigger id={id} className={styles.trigger}>
        <Select.Value placeholder="Choose…" />
        <Select.Icon className={styles.chevron}>
          <Icon name="chevronDown" size={14} />
        </Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Content className={styles.menu} position="popper" sideOffset={4} align="start">
          <Select.Viewport className={styles.viewport}>
            {options.map((o) => (
              <Select.Item key={o.code} value={o.code} className={styles.item}>
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
