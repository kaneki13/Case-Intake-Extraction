import type { IconName } from '../components/Icon'

export interface FlagInfo {
  /** What the reviewer reads, in the rail tooltip, the document header and the no-case panel. */
  label: string
  icon: IconName
  /** caution = worth a second look; neutral = context only. Flags are never a risk state. */
  tone: 'neutral' | 'caution'
}

/** The document flags the backend emits (LLD DocumentFlag). Anything else is shown as-is. */
const KNOWN: Record<string, FlagInfo> = {
  no_adverse_event: { label: 'No adverse event reported', icon: 'ban', tone: 'neutral' },
  missing_minimum_elements: { label: 'Minimum case elements missing', icon: 'listX', tone: 'neutral' },
  unsupported_format: { label: 'Image-only PDF — not processed', icon: 'fileX', tone: 'neutral' },
  multiple_patients: { label: 'Multiple patients', icon: 'users', tone: 'neutral' },
  embedded_instructions_ignored: { label: 'Embedded instructions ignored', icon: 'shield', tone: 'caution' },
  possible_duplicate: { label: 'Possible duplicate', icon: 'copy', tone: 'caution' },
}

/** Flags that explain why a document has no case (FRONTEND.md §4.7). */
const NO_CASE_REASONS = new Set(['no_adverse_event', 'missing_minimum_elements', 'unsupported_format'])

export const flagInfo = (flag: string): FlagInfo | undefined =>
  Object.prototype.hasOwnProperty.call(KNOWN, flag) ? KNOWN[flag] : undefined

export const isNoCaseReason = (flag: string): boolean => NO_CASE_REASONS.has(flag)
