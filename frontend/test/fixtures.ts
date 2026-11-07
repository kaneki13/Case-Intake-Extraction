import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type {
  Catalog, CaseOutput, DocumentDetail, DocumentSummary, Extraction, FieldResult, ReviewCounts,
} from '../src/api/types'

const repoRoot = resolve(__dirname, '../..')
const readJson = <T>(rel: string): T => JSON.parse(readFileSync(resolve(repoRoot, rel), 'utf8')) as T

export const catalogFixture = readJson<Catalog>('catalog/field_catalog.json')
const exampleExtraction = readJson<Extraction>('schema/EXAMPLE-output-D01.json')
const d01Text = readFileSync(resolve(repoRoot, 'documents/D01.txt'), 'utf8')

export function reviewCountsOf(extraction: Extraction): ReviewCounts {
  const byConfidence: Record<string, number> = {}
  for (const level of catalogFixture.confidence_levels) byConfidence[level] = 0
  const counts: ReviewCounts = { conflict: 0, unknown: 0, byConfidence }
  for (const c of extraction.cases) {
    for (const f of Object.values(c.fields)) {
      if (f.status === 'conflict') counts.conflict++
      else if (f.status === 'unknown') counts.unknown++
      else {
        const level = f.confidence
        if (level === undefined || !(level in byConfidence)) {
          throw new Error(`extracted field has confidence ${String(level)}, not a catalog level`)
        }
        byConfidence[level] = (byConfidence[level] ?? 0) + 1
      }
    }
  }
  return counts
}

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T

export const D01: DocumentDetail = {
  id: 'D01', title: 'D01', text: d01Text, extraction: exampleExtraction,
}

export const NC1: DocumentDetail = {
  id: 'NC1', title: 'NC1',
  text: 'Routine follow-up letter. No adverse event was reported by the patient.',
  extraction: {
    document_id: 'NC1', catalog_version: exampleExtraction.catalog_version,
    flags: ['no_adverse_event'], cases: [],
  },
}

export const PDF1: DocumentDetail = {
  id: 'PDF1', title: 'PDF1', text: null,
  extraction: {
    document_id: 'PDF1', catalog_version: exampleExtraction.catalog_version,
    flags: ['unsupported_format'], cases: [],
  },
}

function buildMP1(): DocumentDetail {
  const base = exampleExtraction.cases[0]
  if (!base) throw new Error('EXAMPLE-output-D01.json has no case to copy')
  const mother: CaseOutput = { ...clone(base), case_index: 0, label: 'Mother' }
  const infant: CaseOutput = { ...clone(base), case_index: 1, label: 'Infant' }
  const ids = Object.keys(infant.fields)
  const [id1, id2] = [ids[1], ids[2]]
  if (id1 === undefined || id2 === undefined) throw new Error('example case has too few fields')
  const unknown: FieldResult = { status: 'unknown' }
  const conflict: FieldResult = { status: 'conflict', note: 'Sources disagree (synthetic).' }
  infant.fields[id1] = unknown
  infant.fields[id2] = conflict
  return {
    id: 'MP1', title: 'MP1', text: d01Text,
    extraction: {
      document_id: 'MP1', catalog_version: exampleExtraction.catalog_version,
      flags: ['multiple_patients'], cases: [mother, infant],
    },
  }
}
export const MP1: DocumentDetail = buildMP1()

export const documentFixtures: DocumentDetail[] = [D01, NC1, PDF1, MP1]

export const summaryOf = (d: DocumentDetail): DocumentSummary => ({
  id: d.id, title: d.title, caseCount: d.extraction.cases.length,
  flags: d.extraction.flags ?? [], reviewCounts: reviewCountsOf(d.extraction),
})
export const summaryFixtures: DocumentSummary[] = documentFixtures.map(summaryOf)
