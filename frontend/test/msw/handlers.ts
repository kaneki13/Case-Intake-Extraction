import { http, HttpResponse } from 'msw'
import { catalogFixture, documentFixtures, summaryFixtures } from '../fixtures'

export const problem = (status: number, detail: string) =>
  HttpResponse.json(
    { type: 'about:blank', title: `Error ${status}`, status, detail, instance: '' },
    { status, headers: { 'content-type': 'application/problem+json' } },
  )

export const handlers = [
  http.get('*/api/catalog', () => HttpResponse.json(catalogFixture)),
  http.get('*/api/documents', () => HttpResponse.json(summaryFixtures)),
  http.get('*/api/documents/:id', ({ params }) => {
    const doc = documentFixtures.find((d) => d.id === params.id)
    return doc ? HttpResponse.json(doc) : problem(404, `Document ${String(params.id)} not found`)
  }),
]

/** Per-test override: GET `path` answers a problem+json error. Use with server.use(...). */
export const serverError = (path: string, status: number, detail: string) =>
  http.get(`*${path}`, () => problem(status, detail))

/** Per-test override: GET `path` fails at the network level. */
export const networkError = (path: string) => http.get(`*${path}`, () => HttpResponse.error())
