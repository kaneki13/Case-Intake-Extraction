import { http, HttpResponse } from 'msw'
import type { CatalogField, MergedField, Review, Reviews } from '../../src/api/types'
import { catalogFixture, documentFixtures, duplicateDocFixtures, duplicateFixtures, summaryFixtures } from '../fixtures'

export const problem = (status: number, detail: string) =>
  HttpResponse.json(
    { type: 'about:blank', title: `Error ${status}`, status, detail, instance: '' },
    { status, headers: { 'content-type': 'application/problem+json' } },
  )

const allDocs = [...documentFixtures, ...duplicateDocFixtures]
const fieldOf = (id: string): CatalogField | undefined => catalogFixture.fields.find((f) => f.id === id)

/** In-memory review store: docId -> caseIndex -> fieldId -> review. Reset after every test. */
let store = new Map<string, Reviews>()
export const resetReviews = () => { store = new Map() }
const reviewsOf = (docId: string): Reviews => {
  let r = store.get(docId)
  if (!r) { r = {}; store.set(docId, r) }
  return r
}
const progressOf = (docId: string, caseCount: number) => ({
  reviewed: Object.values(reviewsOf(docId)).reduce((n, c) => n + Object.keys(c).length, 0),
  total: caseCount * catalogFixture.fields.length,
})

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
function locate(params: Record<string, unknown>): Located {
  const docId = String(params.id)
  const doc = allDocs.find((d) => d.id === docId)
  if (!doc) return problem(404, `Document ${docId} not found`)
  const caseIndex = Number(params.caseIndex)
  if (!Number.isInteger(caseIndex)) return problem(400, 'caseIndex must be an integer')
  if (caseIndex < 0 || caseIndex >= doc.extraction.cases.length) return problem(404, `Case ${caseIndex} not found`)
  return { docId, caseIndex }
}

const now = () => new Date().toISOString()

export const handlers = [
  http.get('*/api/catalog', () => HttpResponse.json(catalogFixture)),
  http.get('*/api/documents', () =>
    HttpResponse.json(summaryFixtures.map((s) => ({ ...s, progress: progressOf(s.id, s.caseCount) })))),
  http.get('*/api/duplicates', () => HttpResponse.json(duplicateFixtures)),
  http.get('*/api/documents/:id', ({ params }) => {
    const doc = allDocs.find((d) => d.id === params.id)
    return doc
      ? HttpResponse.json({ ...doc, reviews: reviewsOf(doc.id), progress: progressOf(doc.id, doc.extraction.cases.length) })
      : problem(404, `Document ${String(params.id)} not found`)
  }),
  http.patch('*/api/documents/:id/cases/:caseIndex/fields/:fieldId', async ({ params, request }) => {
    const body = (await request.json()) as { action?: string; value?: unknown }
    const { action } = body
    if (action !== 'accept' && action !== 'reject' && action !== 'edit' && action !== 'reset') {
      return problem(400, `Unknown action ${String(action)}`)
    }
    const hasValue = 'value' in body
    if (action === 'edit' && !hasValue) return problem(400, 'edit requires a value')
    if (action !== 'edit' && hasValue) return problem(400, `${action} takes no value`)
    const loc = locate(params)
    if (loc instanceof Response) return loc
    const fieldId = String(params.fieldId)
    const field = fieldOf(fieldId)
    if (!field) return problem(404, `Unknown field ${fieldId}`)
    const cases = reviewsOf(loc.docId)
    const key = String(loc.caseIndex)
    if (action === 'edit') {
      const msg = invalidEdit(field, body.value)
      if (msg) return problem(400, msg)
    }
    const forCase = (cases[key] ??= {})
    if (action === 'reset') delete forCase[fieldId]
    else {
      const review: Review = action === 'edit'
        ? { state: 'edited', value: body.value, at: now() }
        : { state: action === 'accept' ? 'accepted' : 'rejected', at: now() }
      forCase[fieldId] = review
    }
    return HttpResponse.json(merged(loc.docId, loc.caseIndex, fieldId))
  }),
  http.patch('*/api/documents/:id/cases/:caseIndex/fields', async ({ params, request }) => {
    const body = (await request.json()) as { action?: string; fieldIds?: string[] }
    if (body.action !== 'accept' && body.action !== 'reject') return problem(400, `Unknown action ${String(body.action)}`)
    const loc = locate(params)
    if (loc instanceof Response) return loc
    const unknown = body.fieldIds?.find((id) => !fieldOf(id))
    if (unknown !== undefined) return problem(404, `Unknown field ${unknown}`)
    const forCase = (reviewsOf(loc.docId)[String(loc.caseIndex)] ??= {})
    const targets = catalogFixture.fields
      .map((f) => f.id)
      .filter((id) => (body.fieldIds === undefined || body.fieldIds.includes(id)) && !(id in forCase))
    for (const id of targets) forCase[id] = { state: body.action === 'accept' ? 'accepted' : 'rejected', at: now() }
    return HttpResponse.json(targets.map((id) => merged(loc.docId, loc.caseIndex, id)))
  }),
]

/** Per-test override: GET `path` answers a problem+json error. Use with server.use(...). */
export const serverError = (path: string, status: number, detail: string) =>
  http.get(`*${path}`, () => problem(status, detail))

/** Per-test override: GET `path` fails at the network level. */
export const networkError = (path: string) => http.get(`*${path}`, () => HttpResponse.error())
