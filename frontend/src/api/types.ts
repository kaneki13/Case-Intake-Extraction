export type FieldType = 'string' | 'integer' | 'number' | 'date' | 'coded' | 'coded_multi' | 'string_list';
export interface CatalogField {
  id: string; label: string; section: string; type: FieldType;
  options?: { code: string; label: string }[];
  validation?: { pattern?: string; min?: number; max?: number };
  guidance?: string;
}
export interface Catalog {
  catalog_version: string; confidence_levels: string[];
  sections: { id: string; label: string }[];
  fields: CatalogField[]; statuses: Record<string, string>;
}
export type Status = 'extracted' | 'unknown' | 'conflict';
export interface Provenance { quote: string; start: number; end: number }
export interface FieldResult {
  status: Status; value?: unknown; confidence?: string; provenance?: Provenance[]; note?: string;
}
export interface CaseOutput { case_index: number; label?: string; fields: Record<string, FieldResult> }
export interface Extraction {
  document_id: string; catalog_version: string; model?: string; flags?: string[]; cases: CaseOutput[];
}
export interface ReviewCounts { conflict: number; unknown: number; byConfidence: Record<string, number> }
export interface DocumentSummary {
  id: string; title: string; caseCount: number; flags: string[]; reviewCounts: ReviewCounts;
  progress: Progress;
}
export interface DocumentDetail {
  id: string; title: string; text: string | null; extraction: Extraction;
  reviews: Reviews; progress: Progress;
}
export interface Progress { reviewed: number; total: number }
export type ReviewState = 'accepted' | 'rejected' | 'edited'
/** A stored review as served: `value` only when edited. */
export interface Review { state: ReviewState; value?: unknown; at: string }
/** caseIndex (as a string key) -> fieldId -> review. Only reviewed cases and fields appear. */
export type Reviews = Record<string, Record<string, Review>>
export interface MergedField { fieldId: string; model: FieldResult | null; review: Review | null }
export type ReviewAction = 'accept' | 'reject' | 'edit' | 'reset'
export type BulkAction = 'accept' | 'reject'
export interface DuplicateCaseRef { id: string; caseIndex: number }
export interface DuplicatePair { a: DuplicateCaseRef; b: DuplicateCaseRef; agreements: number }
export interface DuplicateGroup {
  documentIds: string[]; confidence: 'high' | 'medium' | 'low'; reason: string; pairs: DuplicatePair[];
}
/** GET /api/duplicates: the detector's `duplicates.integer-tolerance` (the UI compares with it) and the groups. */
export interface DuplicatesResponse { integerTolerance: number; groups: DuplicateGroup[] }
