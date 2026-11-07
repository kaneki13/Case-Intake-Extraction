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
}
export interface DocumentDetail { id: string; title: string; text: string | null; extraction: Extraction }
