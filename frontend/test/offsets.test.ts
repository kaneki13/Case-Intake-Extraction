import { describe, it, expect } from 'vitest';
import { buildCodePointIndex, toUtf16 } from '../src/lib/offsets';

describe('offsets', () => {
  it('offsetsHandleAstralCharacters', () => {
    const text = '😀 rash here';
    const idx = buildCodePointIndex(text);
    expect(idx.length).toBe(Array.from(text).length + 1);
    expect(toUtf16(idx, 2)).toBe(3);
    expect(toUtf16(idx, 6)).toBe(7);
    expect(text.slice(toUtf16(idx, 2), toUtf16(idx, 6))).toBe('rash');
    expect(toUtf16(idx, idx.length - 1)).toBe(text.length);
  });
  it('empty text has single entry', () => {
    expect(Array.from(buildCodePointIndex(''))).toEqual([0]);
  });
  it('offsetBeyondTextThrowsRangeError', () => {
    const idx = buildCodePointIndex('ab');
    expect(() => toUtf16(idx, 3)).toThrow(RangeError);
    expect(() => toUtf16(idx, -1)).toThrow(RangeError);
    expect(() => toUtf16(idx, 1.5)).toThrow(RangeError);
    expect(toUtf16(idx, 2)).toBe(2);
  });
});
