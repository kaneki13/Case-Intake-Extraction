import { describe, expect, it } from 'vitest'
import { conflictCandidates, levelState, stateOf, toneForRank } from '../src/lib/status'

describe('status presentation', () => {
  it('tonesFollowRankForAnyNumberOfLevels', () => {
    expect([0, 1, 2, 3].map((r) => toneForRank(r, 4))).toEqual(['ok', 'caution', 'caution', 'risk'])
    expect([0, 1].map((r) => toneForRank(r, 2))).toEqual(['ok', 'risk'])
    expect(toneForRank(-1, 3)).toBe('risk')
  })

  it('levelStateShowsTheCatalogNameAndRankTone', () => {
    expect(levelState('sure', ['sure', 'maybe', 'guess'])).toEqual({
      key: 'level:sure', tone: 'ok', icon: '✓', text: 'sure', name: 'Confidence: sure',
    })
    expect(levelState('guess', ['sure', 'maybe', 'guess']).icon).toBe('!')
  })

  it('missingOrUnexpectedStatusIsUnknown', () => {
    expect(stateOf(undefined, ['high']).key).toBe('unknown')
    expect(stateOf({ status: 'bogus' as never }, ['high']).key).toBe('unknown')
  })

  it('conflictCandidatesParsesTheNote', () => {
    expect(conflictCandidates('Candidates: 12 March; 14 March')).toEqual(['12 March', '14 March'])
    expect(conflictCandidates('candidates:a;;b ')).toEqual(['a', 'b'])
    expect(conflictCandidates('Sources disagree.')).toBeNull()
    expect(conflictCandidates(undefined)).toBeNull()
  })
})
