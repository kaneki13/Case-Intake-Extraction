import { createElement, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, getJson } from '../src/api/client'
import { createQueryClient, useCatalog, useDocument, useDocuments } from '../src/api/queries'
import { D01, catalogFixture } from './fixtures'
import { networkError, serverError } from './msw/handlers'
import { server } from './msw/server'

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children)
}

describe('getJson', () => {
  it('problemJsonDetailBecomesApiError', async () => {
    server.use(serverError('/api/documents/ZZ', 404, 'Document ZZ not found'))
    const err = await getJson('/api/documents/ZZ').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 404, title: 'Error 404', detail: 'Document ZZ not found' })
  })

  it('nonJsonErrorUsesStatusText', async () => {
    server.use(
      http.get('/api/documents', () => new HttpResponse('boom', { status: 502, statusText: 'Bad Gateway' })),
    )
    await expect(getJson('/api/documents')).rejects.toMatchObject({ status: 502, detail: 'Bad Gateway' })
  })

  it('emptyStatusTextFallsBackToHttpStatus', async () => {
    // MSW/undici fill in a default statusText, so stub fetch to return a truly empty one.
    const fake = { ok: false, status: 503, statusText: '', headers: new Headers() } as Response
    vi.stubGlobal('fetch', () => Promise.resolve(fake))
    try {
      await expect(getJson('/api/documents')).rejects.toMatchObject({ status: 503, detail: 'HTTP 503' })
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('networkErrorSurfacesAsError', async () => {
    server.use(networkError('/api/catalog'))
    const err = await getJson('/api/catalog').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(0)
    expect((err as ApiError).detail).toMatch(/network/i)
  })

  it('unexpectedShapeIsRejected', async () => {
    server.use(http.get('/api/catalog', () => HttpResponse.json({ nope: true })))
    // Status 0: the request succeeded, so no HTTP status describes what went wrong.
    await expect(getJson('/api/catalog')).rejects.toMatchObject({
      status: 0,
      title: expect.stringMatching(/Unexpected response shape/),
      detail: expect.stringMatching(/^Unexpected response shape: catalog without/),
    })
    server.use(http.get('/api/documents', () => HttpResponse.json({ nope: true })))
    await expect(getJson('/api/documents')).rejects.toMatchObject({ status: 0 })
    server.use(http.get('/api/documents/X', () => HttpResponse.json({ id: 'X', extraction: {} })))
    await expect(getJson('/api/documents/X')).rejects.toMatchObject({ status: 0 })
    server.use(http.get('/api/documents', () => new HttpResponse('not json', { status: 200 })))
    await expect(getJson('/api/documents')).rejects.toMatchObject({
      status: 0,
      detail: expect.stringMatching(/^Unexpected response shape: /),
    })
  })
})

describe('queries', () => {
  it('useCatalogReturnsCatalog', async () => {
    const { result } = renderHook(() => useCatalog(), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.fields.length).toBe(catalogFixture.fields.length)
  })

  it('useDocumentsReturnsSummaries', async () => {
    const { result } = renderHook(() => useDocuments(), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.map((d) => d.id)).toEqual(['D01', 'NC1', 'PDF1', 'MP1'])
    expect(result.current.data?.[3]?.caseCount).toBe(2)
  })

  it('useDocumentReturnsTextAndExtraction', async () => {
    const { result } = renderHook(() => useDocument('D01'), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.text).toBe(D01.text)
    expect(result.current.data?.extraction.cases).toHaveLength(1)
  })

  it('useDocumentNullTextAndNoCases', async () => {
    const pdf = renderHook(() => useDocument('PDF1'), { wrapper: wrapper() })
    const nc = renderHook(() => useDocument('NC1'), { wrapper: wrapper() })
    const mp = renderHook(() => useDocument('MP1'), { wrapper: wrapper() })
    await waitFor(() => {
      expect(pdf.result.current.isSuccess).toBe(true)
      expect(nc.result.current.isSuccess).toBe(true)
      expect(mp.result.current.isSuccess).toBe(true)
    })
    expect(pdf.result.current.data?.text).toBeNull()
    expect(pdf.result.current.data?.extraction.flags).toEqual(['unsupported_format'])
    expect(nc.result.current.data?.extraction.cases).toEqual([])
    expect(nc.result.current.data?.extraction.flags).toEqual(['no_adverse_event'])
    expect(mp.result.current.data?.extraction.cases.map((c) => c.label)).toEqual(['Mother', 'Infant'])
    expect(mp.result.current.data?.extraction.flags).toEqual(['multiple_patients'])
  })

  it('summariesSeedEveryConfidenceLevelInCatalogOrder', async () => {
    const { result } = renderHook(() => useDocuments(), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    for (const s of result.current.data ?? []) {
      expect(Object.keys(s.reviewCounts.byConfidence), s.id).toEqual(catalogFixture.confidence_levels)
    }
  })

  it('useDocumentDisabledWithoutId', () => {
    const { result } = renderHook(() => useDocument(undefined), { wrapper: wrapper() })
    expect(result.current.fetchStatus).toBe('idle')
  })

  it('useDocumentSurfacesApiError', async () => {
    const { result } = renderHook(() => useDocument('NOPE'), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).status).toBe(404)
  })

  it('createQueryClientDefaults', () => {
    const d = createQueryClient().getDefaultOptions().queries
    expect(d).toMatchObject({ staleTime: Infinity, refetchOnWindowFocus: false })
  })

  it('createQueryClientRetriesOnceButNeverAClientError', () => {
    const retry = createQueryClient().getDefaultOptions().queries?.retry
    if (typeof retry !== 'function') throw new Error('retry must be a function of the error')
    const notFound = new ApiError(404, 'Error 404', 'Document ZZ not found')
    const badRequest = new ApiError(400, 'Error 400', 'Bad request')
    const serverDown = new ApiError(503, 'Error 503', 'Unavailable')
    const offline = new ApiError(0, 'Network error', 'offline')
    expect(retry(0, notFound)).toBe(false)
    expect(retry(0, badRequest)).toBe(false)
    expect(retry(0, serverDown)).toBe(true)
    expect(retry(0, offline)).toBe(true)
    expect(retry(0, new Error('boom'))).toBe(true)
    expect(retry(1, serverDown)).toBe(false)
  })
})
