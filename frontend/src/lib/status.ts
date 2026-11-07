import type { FieldResult, Review, ReviewState } from '../api/types'
import { confidenceRank } from './risk'

/** A status colour token from tokens.css (`--ok`, `--caution`, ...). */
export type Tone = 'ok' | 'caution' | 'risk' | 'muted' | 'conflict' | 'edited'

/** How one field state is shown: always an icon, a colour (tone) and a text, plus an accessible name. */
export interface FieldState {
  /** Summary filter key: `level:<name>`, `unknown`, `conflict`, or `review:<kind>` for a review state. */
  key: string
  tone: Tone
  /** One character, decorative (the text carries the meaning). */
  icon: string
  /** The visible word: the catalog's level name, or the status. */
  text: string
  /** Accessible name, e.g. "Confidence: medium". */
  name: string
}

const ICON: Record<Tone, string> = { ok: '✓', caution: '◐', risk: '!', muted: '?', conflict: '⇄', edited: '✎' }

/**
 * Tone by rank in `confidence_levels` (0 = strongest), never by name: rank 0 is ok, the last rank
 * is risk, the ranks between are caution. A level the catalog does not list (-1) is treated as risk.
 */
export function toneForRank(rank: number, levelCount: number): Tone {
  if (rank === 0) return 'ok'
  if (rank > 0 && rank < levelCount - 1) return 'caution'
  return 'risk'
}

export function levelState(level: string, levels: string[]): FieldState {
  const tone = toneForRank(confidenceRank(level, levels), levels.length)
  return { key: `level:${level}`, tone, icon: ICON[tone], text: level, name: `Confidence: ${level}` }
}

export const unknownState = (): FieldState =>
  ({ key: 'unknown', tone: 'muted', icon: ICON.muted, text: 'unknown', name: 'Status: unknown' })
export const conflictState = (): FieldState =>
  ({ key: 'conflict', tone: 'conflict', icon: ICON.conflict, text: 'conflict', name: 'Status: conflict' })
/** What a reviewer has done with a field; `unreviewed` until one of the three review states is stored. */
export type ReviewKind = ReviewState | 'unreviewed'

const REVIEW: Record<ReviewKind, { tone: Tone; icon: string; badge: string; chip: string }> = {
  accepted: { tone: 'ok', icon: '✓', badge: 'Accepted', chip: 'Accepted' },
  rejected: { tone: 'muted', icon: '⊘', badge: 'Rejected', chip: 'Rejected' },
  edited: { tone: 'edited', icon: ICON.edited, badge: 'Manual edit', chip: 'Edited' },
  unreviewed: { tone: 'muted', icon: '○', badge: 'Unreviewed', chip: 'Unreviewed' },
}

/** The review kinds in summary order. */
export const REVIEW_KINDS: readonly ReviewKind[] = ['accepted', 'rejected', 'edited', 'unreviewed']

/** The badge for a review: icon, colour and text ("Manual edit" for an edit). Key `review:<kind>`. */
export function reviewState(kind: ReviewKind): FieldState {
  const r = REVIEW[kind]
  return { key: `review:${kind}`, tone: r.tone, icon: r.icon, text: r.badge, name: r.badge }
}

/** The summary chip for a review kind: same key, tone and icon as the badge, the chip's shorter word. */
export function reviewChipState(kind: ReviewKind): FieldState {
  const r = REVIEW[kind]
  return { ...reviewState(kind), text: r.chip, name: r.chip }
}

export const reviewKindOf = (review: Review | null | undefined): ReviewKind => review?.state ?? 'unreviewed'

/**
 * The state of a model result. A field absent from the output (undefined) is unknown; so is a status
 * the catalog does not define (resultProblems reports it).
 */
export function stateOf(result: FieldResult | undefined, levels: string[]): FieldState {
  if (result?.status === 'extracted') return levelState(result.confidence ?? 'no level', levels)
  if (result?.status === 'conflict') return conflictState()
  return unknownState()
}

/** Problems with a result that the screen must surface rather than hide. Empty when the result is well formed. */
export function resultProblems(result: FieldResult, levels: string[]): string[] {
  const out: string[] = []
  if (!['extracted', 'unknown', 'conflict'].includes(result.status)) out.push(`status "${String(result.status)}" is not a catalog status`)
  if (result.status === 'extracted' && confidenceRank(result.confidence, levels) < 0) {
    out.push(`confidence "${String(result.confidence)}" is not a catalog level`)
  }
  return out
}

/** The candidates of a conflict note written "Candidates: a; b", or null when the note has another shape. */
export function conflictCandidates(note: string | undefined): string[] | null {
  const m = /^\s*candidates\s*:\s*([\s\S]+)$/i.exec(note ?? '')
  if (!m?.[1]) return null
  const items = m[1].split(';').map((s) => s.trim()).filter((s) => s !== '')
  return items.length > 0 ? items : null
}
