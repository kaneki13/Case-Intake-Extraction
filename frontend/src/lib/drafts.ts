import { createContext, createElement, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type { CatalogField } from '../api/types'

/**
 * A reviewer's local edit of one field. The shape follows the catalog type: text for string and date
 * (ISO), a number for integer and number, a code for coded, codes or strings for the list types.
 * null is an explicitly emptied value.
 */
export type DraftValue = string | number | string[] | null

export interface Drafts {
  get: (key: string) => DraftValue | undefined
  has: (key: string) => boolean
  set: (key: string, value: DraftValue) => void
  reset: (key: string) => void
}

/** One draft per field per case per document. */
export const draftKey = (docId: string, caseIndex: number, fieldId: string): string => `${docId}/${caseIndex}/${fieldId}`

const DraftsContext = createContext<Drafts | null>(null)

/**
 * Holds the drafts in memory for the whole session (mounted in the app shell, so they survive
 * switching documents and cases). Nothing is persisted: the PATCH stretch item will save them.
 */
export function DraftsProvider({ children }: { children: ReactNode }) {
  const [map, setMap] = useState<ReadonlyMap<string, DraftValue>>(() => new Map())
  const set = useCallback((key: string, value: DraftValue) => {
    setMap((prev) => new Map(prev).set(key, value))
  }, [])
  const reset = useCallback((key: string) => {
    setMap((prev) => {
      if (!prev.has(key)) return prev
      const next = new Map(prev)
      next.delete(key)
      return next
    })
  }, [])
  const drafts = useMemo<Drafts>(
    () => ({ get: (key) => map.get(key), has: (key) => map.has(key), set, reset }),
    [map, set, reset],
  )
  return createElement(DraftsContext.Provider, { value: drafts }, children)
}

export function useDrafts(): Drafts {
  const drafts = useContext(DraftsContext)
  if (drafts === null) throw new Error('useDrafts: no DraftsProvider above this component')
  return drafts
}

const isEmpty = (v: unknown): boolean => v === null || v === undefined || (Array.isArray(v) && v.length === 0)

/**
 * Whether a control's value equals the model's, so an edit back to the model value is no draft.
 * Empty equals empty (an unknown field has no value; a cleared list is empty). Coded multi-selections
 * compare as sets.
 */
export function sameValue(field: CatalogField, a: unknown, b: unknown): boolean {
  if (isEmpty(a) || isEmpty(b)) return isEmpty(a) && isEmpty(b)
  const norm = (v: unknown) => (field.type === 'coded_multi' && Array.isArray(v) ? [...(v as unknown[])].map(String).sort() : v)
  return JSON.stringify(norm(a)) === JSON.stringify(norm(b))
}
