import * as Tabs from '@radix-ui/react-tabs'
import type { ReactNode } from 'react'
import type { CaseOutput } from '../api/types'
import { RiskPill } from './RiskPill'
import styles from './CaseTabs.module.css'

interface Props {
  cases: CaseOutput[]
  value: number
  onChange: (caseIndex: number) => void
  /** Risky values per case, shown on each tab so a reviewer knows where to look first. */
  riskCounts: number[]
  /** The current case's view. */
  children: ReactNode
}

export const caseLabel = (kase: CaseOutput, i: number): string => kase.label ?? `Case ${i + 1}`

/** One tab per case when a document has more than one; otherwise just the case's view. */
export function CaseTabs({ cases, value, onChange, riskCounts, children }: Props) {
  if (cases.length < 2) return <>{children}</>
  return (
    // Manual activation: arrow keys move focus only, so browsing tabs does not push history entries.
    <Tabs.Root value={String(value)} onValueChange={(v) => onChange(Number(v))} activationMode="manual">
      <Tabs.List className={styles.list} aria-label="Cases">
        {cases.map((kase, i) => {
          const risky = riskCounts[i] ?? 0
          return (
            <Tabs.Trigger key={i} value={String(i)} className={styles.tab}>
              <span>{caseLabel(kase, i)}</span>
              {risky > 0 && (
                <>
                  <span className="visually-hidden">, </span>
                  <RiskPill count={risky} />
                </>
              )}
            </Tabs.Trigger>
          )
        })}
      </Tabs.List>
      {cases.map((_, i) => (
        <Tabs.Content key={i} value={String(i)} className={styles.content}>
          {i === value ? children : null}
        </Tabs.Content>
      ))}
    </Tabs.Root>
  )
}
