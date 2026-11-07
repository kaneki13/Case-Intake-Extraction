import type { FieldResult, ReviewCounts } from '../api/types';

/**
 * Index of a level in the catalog's confidence_levels (0 = strongest).
 * An unknown level returns -1; callers treat it as the weakest.
 */
export function confidenceRank(level: string | undefined, levels: string[]): number {
  return level === undefined ? -1 : levels.indexOf(level);
}

/** Conflict, or extracted with rank > 0 or unrecognised rank (-1). Unknown status is not risky. */
export function isRisky(result: Pick<FieldResult, 'status' | 'confidence'>, levels: string[]): boolean {
  if (result.status === 'conflict') return true;
  if (result.status === 'extracted') return confidenceRank(result.confidence, levels) !== 0;
  return false;
}

/**
 * Risky values in a document summary: conflicts plus every confidence level below the first
 * (catalog rank > 0). A level the catalog does not list counts as risky and is reported.
 */
export function riskCount(counts: ReviewCounts, levels: string[]): number {
  let n = counts.conflict;
  for (const [level, k] of Object.entries(counts.byConfidence)) {
    const rank = confidenceRank(level, levels);
    if (rank === -1) console.error(`riskCount: confidence level "${level}" is not in the catalog; counted as risky`);
    if (rank !== 0) n += k;
  }
  return n;
}
