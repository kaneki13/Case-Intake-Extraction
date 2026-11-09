import { QueryClient, useQuery } from '@tanstack/react-query'
import { ApiError, getJson } from './client'
import type { Catalog, DocumentDetail, DocumentSummary, DuplicatesResponse } from './types'

/** A 4xx answer (missing document, bad request) will not change on a retry. */
const isClientError = (error: unknown): boolean => error instanceof ApiError && error.status >= 400 && error.status < 500

export function createQueryClient(): QueryClient {
  // Data is committed files: never stale, never refetched behind the reviewer's back.
  // One retry for network and server failures only.
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failures, error) => !isClientError(error) && failures < 1,
        staleTime: Infinity,
        refetchOnWindowFocus: false,
      },
    },
  })
}

// Every query hands TanStack's signal to fetch: a query that is cancelled or cleared stops its request.
export const useCatalog = () =>
  useQuery({ queryKey: ['catalog'], queryFn: ({ signal }) => getJson<Catalog>('/api/catalog', { signal }) })

export const useDocuments = () =>
  useQuery({
    queryKey: ['documents'],
    queryFn: ({ signal }) => getJson<DocumentSummary[]>('/api/documents', { signal }),
  })

export const useDocument = (id: string | undefined) =>
  useQuery({
    queryKey: ['document', id],
    queryFn: ({ signal }) => getJson<DocumentDetail>(`/api/documents/${encodeURIComponent(id as string)}`, { signal }),
    enabled: id !== undefined,
  })

/** The duplicate groups and the detector's integer tolerance (the comparison view uses it). */
export const useDuplicates = () =>
  useQuery({
    queryKey: ['duplicates'],
    queryFn: ({ signal }) => getJson<DuplicatesResponse>('/api/duplicates', { signal }),
  })
