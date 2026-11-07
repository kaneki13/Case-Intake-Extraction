export interface Segment { from: number; to: number; fieldIds: string[] } // UTF-16 offsets
export interface SpanInput { fieldId: string; from: number; to: number }

/**
 * Split [0, textLength) into contiguous segments at every span boundary.
 * Each segment lists (in input order, no duplicates) the fields whose spans cover it.
 * Invalid spans (from >= to, or outside [0, textLength]) throw RangeError: callers validate first.
 */
export function buildSegments(textLength: number, spans: SpanInput[]): Segment[] {
  for (const s of spans) {
    if (!Number.isInteger(s.from) || !Number.isInteger(s.to) || s.from >= s.to || s.from < 0 || s.to > textLength) {
      throw new RangeError(`Invalid span [${s.from}, ${s.to}) for text length ${textLength}`);
    }
  }
  if (textLength <= 0) return [];
  const bounds = Array.from(new Set([0, textLength, ...spans.flatMap((s) => [s.from, s.to])])).sort((a, b) => a - b);
  const segments: Segment[] = [];
  for (let i = 0; i + 1 < bounds.length; i++) {
    const from = bounds[i] as number; // i and i+1 are in range by the loop condition
    const to = bounds[i + 1] as number;
    const fieldIds: string[] = [];
    for (const s of spans) {
      if (s.from <= from && s.to >= to && !fieldIds.includes(s.fieldId)) fieldIds.push(s.fieldId);
    }
    segments.push({ from, to, fieldIds });
  }
  return segments;
}
