import { createElement, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useDocument, useDocuments, useDuplicates } from '../src/api/queries'
import { useBulkReview, useReviewField } from '../src/api/reviews'
import type { CatalogField } from '../src/api/types'
import { catalogFixture, duplicateFixtures, duplicatesOf } from './fixtures'
import { server } from './msw/server'

// Listeners added by capture() go even when a test fails half-way.
afterEach(() => {
  server.events.removeAllListeners()
})

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}
const intField = catalogFixture.fields.find((f): f is CatalogField => f.type === 'integer' && f.validation?.max !== undefined)
if (!intField) throw new Error('catalog has no bounded integer field')
const [f0, f1, f2] = catalogFixture.fields.map((f) => f.id)

function capture() {
  const seen: { url: string; body: unknown }[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PATCH') seen.push({ url: new URL(request.url).pathname, body: await request.clone().json() })
  })
  return seen
}

describe('review mutations', () => {
  it('patchSendsActionAndValue', async () => {
    const seen = capture()
    const { wrapper } = setup()
    const { result } = renderHook(() => useReviewField('D01'), { wrapper })
    let edited: unknown
    await act(async () => {
      edited = await result.current.mutateAsync({ caseIndex: 0, fieldId: intField.id, action: 'edit', value: 40 })
      await result.current.mutateAsync({ caseIndex: 0, fieldId: f0 as string, action: 'accept' })
    })
    expect(seen[0]).toEqual({ url: `/api/documents/D01/cases/0/fields/${intField.id}`, body: { action: 'edit', value: 40 } })
    expect(seen[1]?.body).toEqual({ action: 'accept' })
    expect(edited).toMatchObject({ fieldId: intField.id, review: { state: 'edited', value: 40 } })
  })

  it('bulkSendsFieldIdsOrAll', async () => {
    const seen = capture()
    const { wrapper } = setup()
    const { result } = renderHook(() => useBulkReview('D01'), { wrapper })
    let some: unknown[] = []
    let rest: unknown[] = []
    await act(async () => {
      some = await result.current.mutateAsync({ caseIndex: 0, action: 'accept', fieldIds: [f0 as string, f1 as string] })
      rest = await result.current.mutateAsync({ caseIndex: 0, action: 'reject' })
    })
    expect(seen[0]).toEqual({ url: '/api/documents/D01/cases/0/fields', body: { action: 'accept', fieldIds: [f0, f1] } })
    expect(seen[1]?.body).toEqual({ action: 'reject' })
    expect(some).toHaveLength(2)
    expect(rest).toHaveLength(catalogFixture.fields.length - 2) // unreviewed only
  })

  it('mutationInvalidatesDocumentAndList', async () => {
    const { client, wrapper } = setup()
    const docs = renderHook(() => ({ d: useDocument('D01'), l: useDocuments() }), { wrapper })
    await waitFor(() => expect(docs.result.current.d.data && docs.result.current.l.data).toBeTruthy())
    expect(docs.result.current.d.data?.progress.reviewed).toBe(0)
    const spy = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useReviewField('D01'), { wrapper })
    await act(async () => {
      await result.current.mutateAsync({ caseIndex: 0, fieldId: f2 as string, action: 'accept' })
    })
    expect(spy).toHaveBeenCalledWith({ queryKey: ['document', 'D01'] })
    expect(spy).toHaveBeenCalledWith({ queryKey: ['documents'] })
    await waitFor(() => expect(docs.result.current.d.data?.progress.reviewed).toBe(1))
    expect(docs.result.current.d.data?.reviews['0']?.[f2 as string]?.state).toBe('accepted')
    await waitFor(() => expect(docs.result.current.l.data?.find((s) => s.id === 'D01')?.progress.reviewed).toBe(1))
  })

  it('problemJson400SurfacesValidationMessage', async () => {
    const { wrapper } = setup()
    const { result } = renderHook(() => useReviewField('D01'), { wrapper })
    await act(async () => {
      await result.current
        .mutateAsync({ caseIndex: 0, fieldId: intField.id, action: 'edit', value: (intField.validation?.max ?? 0) + 1 })
        .catch(() => undefined)
    })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toMatchObject({ status: 400 })
    expect(result.current.error?.message).toContain(`Invalid value for field ${intField.id}`)
  })

  it('unknownCaseIs404AndSegmentsAreEncoded', async () => {
    server.use(http.patch('*/api/documents/:id/cases/:caseIndex/fields', ({ params }) =>
      HttpResponse.json([params.id], { status: 200 })))
    const { wrapper } = setup()
    const { result } = renderHook(() => ({ one: useReviewField('D01'), bulk: useBulkReview('a/b c') }), { wrapper })
    await act(async () => {
      await result.current.one.mutateAsync({ caseIndex: 9, fieldId: f0 as string, action: 'accept' }).catch(() => undefined)
    })
    await waitFor(() => expect(result.current.one.error).toMatchObject({ status: 404 }))
    let ids: unknown[] = []
    await act(async () => { ids = await result.current.bulk.mutateAsync({ caseIndex: 0, action: 'accept' }) })
    expect(ids).toEqual(['a/b c'])
  })
})

describe('mock review store', () => {
  const patch = (path: string, body: unknown) =>
    fetch(new URL(path, location.href), { method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })

  it('resolvesTheDocumentBeforeTheAction', async () => {
    expect((await patch(`/api/documents/NOPE/cases/0/fields/${f0 as string}`, { action: 'bogus' })).status).toBe(404)
    expect((await patch('/api/documents/NOPE/cases/0/fields', { action: 'bogus' })).status).toBe(404)
    expect((await patch(`/api/documents/D01/cases/0/fields/${f0 as string}`, { action: 'bogus' })).status).toBe(400)
    // A JSON null is no value, as on the server.
    expect((await patch(`/api/documents/D01/cases/0/fields/${f0 as string}`, { action: 'edit', value: null })).status).toBe(400)
  })

  it('keepsNoEmptyCaseMaps', async () => {
    const reviews = async () => ((await (await fetch(new URL('/api/documents/MP1', location.href))).json()) as { reviews: unknown }).reviews
    await patch(`/api/documents/MP1/cases/1/fields/${f0 as string}`, { action: 'accept' })
    expect(Object.keys((await reviews()) as object)).toEqual(['1'])
    await patch(`/api/documents/MP1/cases/1/fields/${f0 as string}`, { action: 'reset' })
    expect(await reviews()).toEqual({})
    // A bulk action that changes nothing (every listed field reviewed or none listed) creates nothing.
    await patch('/api/documents/MP1/cases/0/fields', { action: 'accept', fieldIds: [] })
    expect(await reviews()).toEqual({})
  })
})

describe('useDuplicates', () => {
  it('useDuplicatesReturnsGroups', async () => {
    const { wrapper } = setup()
    const { result } = renderHook(() => useDuplicates(), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(duplicatesOf(duplicateFixtures))
    expect(result.current.data?.integerTolerance).toBe(1)
    expect(result.current.data?.groups[0]?.pairs[0]).toMatchObject({ a: { id: 'DUP_A', caseIndex: 0 }, b: { id: 'DUP_B' } })
  })
})
