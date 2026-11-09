import { describe, expect, it } from 'vitest'
import type { CatalogField, FieldResult, FieldType } from '../src/api/types'
import { COMPARISONS, compareFieldValues, comparisonClass } from '../src/lib/compare'

/** A synthetic field of the given type: the comparator reads only the type. */
const fieldOf = (type: FieldType): CatalogField => ({ id: `synthetic_${type}`, label: type, section: 's', type })
const x = (value: unknown): FieldResult => ({ status: 'extracted', value, confidence: 'high' })
const unknown: FieldResult = { status: 'unknown' }
const conflict: FieldResult = { status: 'conflict' }

/** The tolerance is always the server's (GET /api/duplicates); 1 is the application.yml value. */
const compare = (type: FieldType, a: FieldResult | undefined, b: FieldResult | undefined, tolerance = 1) =>
  compareFieldValues(fieldOf(type), a, b, tolerance)

describe('compareFieldValues presence (only extracted values count)', () => {
  it('bothAbsentWhenNeitherSideIsExtracted', () => {
    expect(compare('string', undefined, undefined)).toBe('both-absent')
    expect(compare('string', unknown, conflict)).toBe('both-absent')
    expect(compare('string', x(null), unknown)).toBe('both-absent')
  })

  it('oneSidedWhenOnlyOneSideIsExtracted', () => {
    expect(compare('string', x('EW'), undefined)).toBe('one-sided')
    expect(compare('string', unknown, x('EW'))).toBe('one-sided')
    expect(compare('string', x('EW'), conflict)).toBe('one-sided')
  })
})

describe('compareFieldValues by type (STRETCH §2.2)', () => {
  it('stringEqualAfterTrimAndCaseFold', () => {
    expect(compare('string', x('  Digoxin '), x('digoxin'))).toBe('match')
    expect(compare('string', x('digoxin'), x('digitoxin'))).toBe('differ')
  })

  it('integerEqualWithinTolerance', () => {
    expect(compare('integer', x(81), x(82))).toBe('match')
    expect(compare('integer', x(81), x(80))).toBe('match')
    expect(compare('integer', x(81), x(83))).toBe('differ')
    expect(compare('integer', x(81), x(82), 0)).toBe('differ')
    expect(compare('integer', x(81), x(83), 2)).toBe('match')
  })

  it('numberNumericallyEqual', () => {
    expect(compare('number', x(500), x(500.0))).toBe('match')
    expect(compare('number', x(0.25), x(0.25))).toBe('match')
    expect(compare('number', x(500), x(501))).toBe('differ')
  })

  it('dateEqual', () => {
    expect(compare('date', x('2026-01-05'), x('2026-01-05'))).toBe('match')
    expect(compare('date', x('2026-01-05'), x('2026-01-06'))).toBe('differ')
  })

  it('codedEqualWithoutFolding', () => {
    expect(compare('coded', x('female'), x('female'))).toBe('match')
    expect(compare('coded', x('female'), x('male'))).toBe('differ')
    expect(compare('coded', x('female'), x('Female'))).toBe('differ')
  })

  it('codedMultiEqualAsSets', () => {
    expect(compare('coded_multi', x(['death', 'hospitalization']), x(['hospitalization', 'death', 'death']))).toBe('match')
    expect(compare('coded_multi', x([]), x([]))).toBe('match')
    expect(compare('coded_multi', x(['death']), x(['death', 'hospitalization']))).toBe('differ')
  })

  it('stringListBothEmptyOrOverlappingAfterFold', () => {
    expect(compare('string_list', x([]), x([]))).toBe('match')
    expect(compare('string_list', x(['Rash ', 'nausea']), x(['rash', 'fever']))).toBe('match')
    expect(compare('string_list', x(['rash']), x(['fever']))).toBe('differ')
    expect(compare('string_list', x([]), x(['fever']))).toBe('differ')
  })

  it('malformedExtractedValueThrows', () => {
    expect(() => compare('integer', x('81'), x(81))).toThrow(/integer/)
    expect(() => compare('integer', x(81.5), x(81))).toThrow(/integer/)
    expect(() => compare('string', x(1), x('1'))).toThrow(/string/)
    expect(() => compare('coded_multi', x('death'), x(['death']))).toThrow(/list/)
    expect(() => compare('string_list', x([1]), x(['1']))).toThrow(/string/)
  })
})

describe('comparisonClass', () => {
  it('comparisonClassIsDistinctPerState', () => {
    const classes = COMPARISONS.map(comparisonClass)
    expect(COMPARISONS).toEqual(['match', 'differ', 'one-sided', 'both-absent'])
    for (const c of classes) expect(c).toMatch(/\S/)
    expect(new Set(classes).size).toBe(COMPARISONS.length)
  })
})
