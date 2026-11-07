import { describe, it, expect } from 'vitest';
import { buildSegments } from '../src/lib/segments';

describe('segments', () => {
  it('overlappingSpansSplitIntoSegments', () => {
    const segs = buildSegments(12, [
      { fieldId: 'A', from: 2, to: 8 },
      { fieldId: 'B', from: 5, to: 10 },
    ]);
    expect(segs).toEqual([
      { from: 0, to: 2, fieldIds: [] },
      { from: 2, to: 5, fieldIds: ['A'] },
      { from: 5, to: 8, fieldIds: ['A', 'B'] },
      { from: 8, to: 10, fieldIds: ['B'] },
      { from: 10, to: 12, fieldIds: [] },
    ]);
  });
  it('empty text gives no segments', () => {
    expect(buildSegments(0, [])).toEqual([]);
  });
  it('no spans gives one plain segment', () => {
    expect(buildSegments(5, [])).toEqual([{ from: 0, to: 5, fieldIds: [] }]);
  });
  it('same field twice is listed once', () => {
    const segs = buildSegments(6, [
      { fieldId: 'A', from: 0, to: 4 },
      { fieldId: 'A', from: 2, to: 6 },
    ]);
    expect(segs.map((s) => s.fieldIds)).toEqual([['A'], ['A'], ['A']]);
  });
  it('invalid spans throw RangeError', () => {
    expect(() => buildSegments(5, [{ fieldId: 'A', from: 3, to: 3 }])).toThrow(RangeError);
    expect(() => buildSegments(5, [{ fieldId: 'A', from: 4, to: 2 }])).toThrow(RangeError);
    expect(() => buildSegments(5, [{ fieldId: 'A', from: 0, to: 6 }])).toThrow(RangeError);
    expect(() => buildSegments(5, [{ fieldId: 'A', from: -1, to: 2 }])).toThrow(RangeError);
  });
});
