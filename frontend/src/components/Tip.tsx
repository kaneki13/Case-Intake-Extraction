import * as Tooltip from '@radix-ui/react-tooltip'
import type { ReactElement } from 'react'
import styles from './Tip.module.css'

/**
 * A short hover/focus tooltip. The label must also be the trigger's accessible name
 * (aria-label or visible text): the tooltip is a convenience for sighted pointer users.
 */
export function Tip({ label, children }: { label: string; children: ReactElement }) {
  return (
    <Tooltip.Root>
      <Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
      <Tooltip.Portal>
        <Tooltip.Content className={styles.content} side="top" sideOffset={6} collisionPadding={8}>
          {label}
          <Tooltip.Arrow className={styles.arrow} width={10} height={5} />
        </Tooltip.Content>
      </Tooltip.Portal>
    </Tooltip.Root>
  )
}
