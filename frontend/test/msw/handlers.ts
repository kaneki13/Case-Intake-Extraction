import { http, HttpResponse } from 'msw'
import type { CatalogField, DocumentDetail, MergedField, Review, Reviews } from '../../src/api/types'
import { catalogFixture, documentFixtures, duplicateDocFixtures, duplicateFixtures, duplicatesOf, summaryFixtures } from '../fixtures'

export const problem = (status: number, detail: string) =>
  HttpResponse.json(
    { type: 'about:blank', title: `Error ${status}`, status, detail, instance: '' },
    { status, headers: { 'content-type': 'application/problem+json' } },
  )

const allDocs = [...documentFixtures, ...duplicateDocFixtures]
const fieldOf = (id: string): CatalogField | undefined => catalogFixture.fields.find((f) => f.id === id)

/** In-memory review store: docId -> caseIndex -> fieldId -> review. Reset after every test. Never holds empty maps. */
let store = new Map<string, Reviews>()
export const resetReviews = () => { store = new Map() }
/** The stored reviews of a document, read-only: nothing is created for an unreviewed document. */
const reviewsOf = (docId: string): Reviews => store.get(docId) ?? {}
/** Change one case's reviews; empty case maps and empty documents are dropped, as on the server. */
function updateCase(docId: string, caseIndex: number, change: (forCase: Record<string, Review>) => void) {
  const doc: Reviews = { ...reviewsOf(docId) }
  const key = String(caseIndex)
  const forCase = { ...(doc[key] ?? {}) }
  change(forCase)
  if (Object.keys(forCase).length > 0) doc[key] = forCase
  else delete doc[key]
  if (Object.keys(doc).length > 0) store.set(docId, doc)
  else store.delete(docId)
}
const progressOf = (docId: string, caseCount: number) => ({
  reviewed: Object.values(reviewsOf(docId)).reduce((n, c) => n + Object.keys(c).length, 0),
  total: caseCount * catalogFixture.fields.length,
})

/** A document as GET /api/documents/:id serves it: with the stored reviews and progress. For per-test overrides. */
export const served = (doc: DocumentDetail): DocumentDetail =>
  ({ ...doc, reviews: reviewsOf(doc.id), progress: progressOf(doc.id, doc.extraction.cases.length) })

/** Simple catalog-type check standing in for the backend validator. */
function invalidEdit(field: CatalogField, value: unknown): string | null {
  const bad = (why: string) => `Invalid value for field ${field.id}: ${why}`
  const v = field.validation
  switch (field.type) {
    case 'integer':
    case 'number':
      if (typeof value !== 'number' || (field.type === 'integer' && !Number.isInteger(value))) return bad(`expected ${field.type}`)
      if (v?.min !== undefined && value < v.min) return bad(`value ${value} below min ${v.min}`)
      if (v?.max !== undefined && value > v.max) return bad(`value ${value} above max ${v.max}`)
      return null
    case 'coded':
      return typeof value === 'string' && field.options?.some((o) => o.code === value) ? null : bad('not an allowed code')
    case 'coded_multi':
    case 'string_list':
      return Array.isArray(value) && value.every((x) => typeof x === 'string') ? null : bad('expected a list of strings')
    default:
      return typeof value === 'string' ? null : bad('expected a string')
  }
}

function merged(docId: string, caseIndex: number, fieldId: string): MergedField {
  const doc = allDocs.find((d) => d.id === docId)
  return {
    fieldId,
    model: doc?.extraction.cases[caseIndex]?.fields[fieldId] ?? null,
    review: reviewsOf(docId)[String(caseIndex)]?.[fieldId] ?? null,
  }
}

type Located = { docId: string; caseIndex: number } | Response
const findDoc = (id: unknown) => allDocs.find((d) => d.id === String(id))
/** The case of a located document (backend order: the document, then the action, then the case). */
function locateCase(docId: string, rawIndex: unknown): Located {
  const doc = findDoc(docId)
  if (!doc) return problem(404, `Document ${docId} not found`)
  const caseIndex = Number(rawIndex)
  if (!Number.isInteger(caseIndex)) return problem(400, 'caseIndex must be an integer')
  if (caseIndex < 0 || caseIndex >= doc.extraction.cases.length) return problem(404, `Document ${docId} has no case ${caseIndex}`)
  return { docId, caseIndex }
}

const now = () => new Date().toISOString()

export const handlers = [
  http.get('*/api/catalog', () => HttpResponse.json(catalogFixture)),
  http.get('*/api/documents', () =>
    HttpResponse.json(summaryFixtures.map((s) => ({ ...s, progress: progressOf(s.id, s.caseCount) })))),
  http.get('*/api/duplicates', () => HttpResponse.json(duplicatesOf(duplicateFixtures))),
  http.get('*/api/documents/:id', ({ params }) => {
    const doc = findDoc(params.id)
    return doc ? HttpResponse.json(served(doc)) : problem(404, `Document ${String(params.id)} not found`)
  }),
  // Same order as ReviewService.apply: document 404, action/value 400, case 404, field 404, edit value 400.
  http.patch('*/api/documents/:id/cases/:caseIndex/fields/:fieldId', async ({ params, request }) => {
    const docId = String(params.id)
    if (!findDoc(docId)) return problem(404, `Document ${docId} not found`)
    const body = (await request.json()) as { action?: string; value?: unknown }
    const { action } = body
    if (action !== 'accept' && action !== 'reject' && action !== 'edit' && action !== 'reset') {
      return problem(400, `Unknown action ${String(action)}`)
    }
    // As on the server, a JSON null is no value.
    const hasValue = body.value !== undefined && body.value !== null
    if (action === 'edit' && !hasValue) return problem(400, 'An edit needs a value')
    if (action !== 'edit' && hasValue) return problem(400, 'A value is only allowed with action edit')
    const loc = locateCase(docId, params.caseIndex)
    if (loc instanceof Response) return loc
    const fieldId = String(params.fieldId)
    const field = fieldOf(fieldId)
    if (!field) return problem(404, `No catalog field with id ${fieldId}`)
    if (action === 'edit') {
      const msg = invalidEdit(field, body.value)
      if (msg) return problem(400, msg)
    }
    updateCase(loc.docId, loc.caseIndex, (forCase) => {
      const current = forCase[fieldId]
      if (action === 'reset') delete forCase[fieldId]
      else if (action === 'edit') forCase[fieldId] = { state: 'edited', value: body.value, at: now() }
      // As on the server: accept confirms an edit (kept) or an accept; reject keeps a reject.
      else if (action === 'accept' && (current?.state === 'edited' || current?.state === 'accepted')) return
      else if (action === 'reject' && current?.state === 'rejected') return
      else forCase[fieldId] = { state: action === 'accept' ? 'accepted' : 'rejected', at: now() }
    })
    return HttpResponse.json(merged(loc.docId, loc.caseIndex, fieldId))
  }),
  // Same order as ReviewService.applyBulk: document 404, action 400, case 404, field ids 404.
  http.patch('*/api/documents/:id/cases/:caseIndex/fields', async ({ params, request }) => {
    const docId = String(params.id)
    if (!findDoc(docId)) return problem(404, `Document ${docId} not found`)
    const body = (await request.json()) as { action?: string; fieldIds?: string[] }
    if (body.action !== 'accept' && body.action !== 'reject') return problem(400, `Unknown action ${String(body.action)}`)
    const loc = locateCase(docId, params.caseIndex)
    if (loc instanceof Response) return loc
    const unknown = body.fieldIds?.find((id) => !fieldOf(id))
    if (unknown !== undefined) return problem(404, `No catalog field with id ${unknown}`)
    const reviewed = reviewsOf(loc.docId)[String(loc.caseIndex)] ?? {}
    const targets = catalogFixture.fields
      .map((f) => f.id)
      .filter((id) => (body.fieldIds === undefined || body.fieldIds.includes(id)) && !(id in reviewed))
    const state = body.action === 'accept' ? 'accepted' : 'rejected'
    updateCase(loc.docId, loc.caseIndex, (forCase) => {
      for (const id of targets) forCase[id] = { state, at: now() }
    })
    return HttpResponse.json(targets.map((id) => merged(loc.docId, loc.caseIndex, id)))
  }),
]

/** Per-test override: GET `path` answers a problem+json error. Use with server.use(...). */
export const serverError = (path: string, status: number, detail: string) =>
  http.get(`*${path}`, () => problem(status, detail))

/** Per-test override: GET `path` fails at the network level. */
export const networkError = (path: string) => http.get(`*${path}`, () => HttpResponse.error())
