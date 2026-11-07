import { describe, it, expect, vi } from 'vitest';
import { isRisky, confidenceRank, riskCount } from '../src/lib/risk';

const levels = ['x', 'y', 'z'];
describe('risk', () => {
  it('riskUsesCatalogRankNotNames', () => {
    expect(isRisky({ status: 'extracted', confidence: 'x' }, levels)).toBe(false);
    expect(isRisky({ status: 'extracted', confidence: 'y' }, levels)).toBe(true);
    expect(isRisky({ status: 'extracted', confidence: 'z' }, levels)).toBe(true);
  });
  it('conflict is risky, unknown is not', () => {
    expect(isRisky({ status: 'conflict' }, levels)).toBe(true);
    expect(isRisky({ status: 'unknown' }, levels)).toBe(false);
  });
  it('unknown or missing confidence on extracted is risky', () => {
    expect(isRisky({ status: 'extracted', confidence: 'weird' }, levels)).toBe(true);
    expect(isRisky({ status: 'extracted' }, levels)).toBe(true);
  });
  it('confidenceRank', () => {
    expect(confidenceRank('y', levels)).toBe(1);
    expect(confidenceRank('nope', levels)).toBe(-1);
  });
});

describe('riskCount', () => {
  it('riskCountIsConflictsPlusEveryLevelBelowTheFirst', () => {
    const counts = { conflict: 2, unknown: 7, byConfidence: { x: 9, y: 3, z: 1 } };
    expect(riskCount(counts, levels)).toBe(6);
    expect(riskCount(counts, ['z', 'y', 'x'])).toBe(14);
  });
  it('riskCountTreatsAnUnlistedLevelAsRiskyAndReportsIt', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(riskCount({ conflict: 0, unknown: 0, byConfidence: { x: 1, w: 4 } }, levels)).toBe(4);
    expect(error).toHaveBeenCalledOnce();
    error.mockRestore();
  });
});
