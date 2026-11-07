/** Backend offsets count Unicode code points; JS strings index UTF-16 code units. */

/** Entry i is the UTF-16 offset of code point i; the last entry equals text.length. */
export function buildCodePointIndex(text: string): Uint32Array {
  const out: number[] = [];
  let i = 0;
  while (i < text.length) {
    out.push(i);
    const cp = text.codePointAt(i) as number;
    i += cp > 0xffff ? 2 : 1;
  }
  out.push(text.length);
  return Uint32Array.from(out);
}

/** Convert a code-point offset to a UTF-16 offset. Throws RangeError if out of [0, codePointCount]. */
export function toUtf16(index: Uint32Array, cp: number): number {
  const count = index.length - 1;
  if (!Number.isInteger(cp) || cp < 0 || cp > count) {
    throw new RangeError(`Code point offset ${cp} outside [0, ${count}]`);
  }
  return index[cp] as number; // in range: checked above
}
