import { QueryClient, useQuery } from '@tanstack/react-query'
import { ApiError, getJson } from './client'
import type { Catalog, DocumentDetail, DocumentSummary } from './types'

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

export const useCatalog = () =>
  useQuery({ queryKey: ['catalog'], queryFn: () => getJson<Catalog>('/api/catalog') })

export const useDocuments = () =>
  useQuery({ queryKey: ['documents'], queryFn: () => getJson<DocumentSummary[]>('/api/documents') })

export const useDocument = (id: string | undefined) =>
  useQuery({
    queryKey: ['document', id],
    queryFn: () => getJson<DocumentDetail>(`/api/documents/${encodeURIComponent(id as string)}`),
    enabled: id !== undefined,
  })
