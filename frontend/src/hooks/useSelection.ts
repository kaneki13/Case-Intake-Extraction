import { useCallback, useEffect, useMemo } from 'react'
import { useLocation, useParams, useSearchParams } from 'react-router'
import { useDocument } from '../api/queries'

/**
 * How the fields panel should bring a selected row into view. Set by selections made away from
 * the row itself: a quote click (`scroll`) or the keyboard (`focus`, which also moves focus to it).
 * A row click needs neither: the row is already under the pointer.
 */
export type Reveal = 'scroll' | 'focus'

export interface RevealRequest {
  how: Reveal
  /** The navigation that asked for it (history entry key): each request is handled once. */
  key: string
}

export interface SelectOptions {
  reveal?: Reveal
}

export interface Selection {
  /** The document in the path, `/documents/:id`; undefined on `/`. */
  docId: string | undefined
  /** `?case=`, validated against the loaded document; 0 when absent or invalid. */
  caseIndex: number
  /** `?field=`, or null when nothing is selected. */
  fieldId: string | null
  /** Select a field (or clear with null). Replaces history: field hops must not flood Back. */
  select: (fieldId: string | null, options?: SelectOptions) => void
  /** The reveal asked for by the navigation that made the current selection, if any. */
  reveal: RevealRequest | null
  /** Switch case. Pushes history: a case is a distinct view. Keeps the selected field. */
  setCase: (caseIndex: number) => void
}

/**
 * Parse `?case=`. Before the document has loaded only the syntax can be checked;
 * afterwards an index beyond the cases is invalid too. Invalid means case 0 plus a reason.
 */
function parseCase(raw: string | null, caseCount: number | undefined): { index: number; problem: string | null } {
  if (raw === null) return { index: 0, problem: null }
  if (!/^\d+$/.test(raw)) return { index: 0, problem: `case "${raw}" is not a case index` }
  const n = Number(raw)
  if (caseCount !== undefined && n >= Math.max(caseCount, 1)) {
    return { index: 0, problem: `case ${n} is out of range (the document has ${caseCount} case(s))` }
  }
  return { index: n, problem: null }
}

/** The review selection (document, case, field), held in the URL. */
export function useSelection(): Selection {
  const { id: docId } = useParams()
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  // Same query key as the panes, so this reads the cache rather than fetching again.
  const { data } = useDocument(docId)
  const caseCount = data?.extraction.cases.length

  const { index: caseIndex, problem } = parseCase(params.get('case'), caseCount)
  const fieldId = params.get('field') || null

  useEffect(() => {
    if (problem) console.error(`useSelection: ${problem}; showing the first case instead`)
  }, [problem])

  const select = useCallback(
    (next: string | null, options?: SelectOptions) => {
      setParams(
        (prev) => {
          const p = new URLSearchParams(prev)
          if (next === null) p.delete('field')
          else p.set('field', next)
          return p
        },
        // The reveal rides in history state, not the URL: it describes this navigation, not the view.
        { replace: true, state: next !== null && options?.reveal ? { reveal: options.reveal } : null },
      )
    },
    [setParams],
  )

  const how = revealOf(location.state)
  const reveal = useMemo<RevealRequest | null>(
    () => (how && fieldId !== null ? { how, key: location.key } : null),
    [how, fieldId, location.key],
  )

  const setCase = useCallback(
    (next: number) => {
      const valid = Number.isInteger(next) && next >= 0 && (caseCount === undefined || next < caseCount)
      if (!valid) {
        console.error(`useSelection: setCase(${next}) ignored; the document has ${caseCount ?? '?'} case(s)`)
        return
      }
      setParams((prev) => {
        const p = new URLSearchParams(prev)
        p.set('case', String(next))
        return p
      })
    },
    [setParams, caseCount],
  )

  return useMemo(
    () => ({ docId, caseIndex, fieldId, select, reveal, setCase }),
    [docId, caseIndex, fieldId, select, reveal, setCase],
  )
}

/** The reveal carried in a history entry's state, if any. */
export function revealOf(state: unknown): Reveal | null {
  if (typeof state !== 'object' || state === null || !('reveal' in state)) return null
  const how = (state as { reveal: unknown }).reveal
  return how === 'scroll' || how === 'focus' ? how : null
}
