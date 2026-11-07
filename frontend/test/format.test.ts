import { describe, it, expect, vi, afterEach } from 'vitest';
import { formatValue } from '../src/lib/format';
import type { CatalogField } from '../src/api/types';

const f = (type: CatalogField['type'], extra: Partial<CatalogField> = {}): CatalogField =>
  ({ id: 'k', label: 'K', section: 's', type, ...extra });
const opts = [{ code: 'a', label: 'Alpha' }, { code: 'b', label: 'Beta' }];

afterEach(() => vi.restoreAllMocks());

describe('formatValue', () => {
  it('scalars', () => {
    expect(formatValue(f('string'), 'hi')).toBe('hi');
    expect(formatValue(f('integer'), 42)).toBe('42');
    expect(formatValue(f('number'), 3.5)).toBe('3.5');
  });
  it('formatsCodedAsOptionLabel', () => {
    expect(formatValue(f('coded', { options: opts }), 'b')).toBe('Beta');
  });
  it('unknown code is explicit and logged', () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(formatValue(f('coded', { options: opts }), 'zz')).toBe('Unknown option "zz"');
    expect(err).toHaveBeenCalled();
  });
  it('coded_multi joins labels', () => {
    expect(formatValue(f('coded_multi', { options: opts }), ['a', 'b'])).toBe('Alpha, Beta');
  });
  it('emptyStringListIsNoneStated', () => {
    expect(formatValue(f('string_list'), [])).toBe('None stated');
    expect(formatValue(f('string_list'), ['p', 'q'])).toBe('p, q');
  });
  it('wrong runtime type is Invalid value and logged', () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(formatValue(f('integer'), 'x')).toBe('Invalid value');
    expect(formatValue(f('string_list'), 'x')).toBe('Invalid value');
    expect(formatValue(f('date'), 'not-a-date')).toBe('Invalid value');
    expect(formatValue(f('string'), undefined)).toBe('Invalid value');
    expect(err).toHaveBeenCalledTimes(4);
  });
  it('date is formatted without timezone shift', () => {
    const orig = process.env.TZ;
    try {
      for (const tz of ['Pacific/Kiritimati', 'America/Los_Angeles', 'UTC']) {
        process.env.TZ = tz;
        expect(formatValue(f('date'), '2026-03-04')).toBe('4 Mar 2026');
        expect(formatValue(f('date'), '2026-01-01')).toBe('1 Jan 2026');
        expect(formatValue(f('date'), '2025-12-31')).toBe('31 Dec 2025');
      }
    } finally {
      if (orig === undefined) delete process.env.TZ; else process.env.TZ = orig;
    }
  });
  it('rejects impossible dates', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(formatValue(f('date'), '2026-02-30')).toBe('Invalid value');
  });
});
