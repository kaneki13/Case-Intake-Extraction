import { QueryClient } from '@tanstack/react-query'

/** Every client a test created; the shared cleanup (vitest.setup.ts) clears them, so no query outlives its test. */
const clients = new Set<QueryClient>()

/** A fresh client per test: no retries, no background refetch, no gc timers. */
export function createTestQueryClient(): QueryClient {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity, gcTime: Infinity, refetchOnWindowFocus: false },
    },
  })
  clients.add(client)
  return client
}

/** Cancels and drops every query of the test's clients (their fetches abort through the query signal). */
export function clearTestQueryClients(): void {
  for (const client of clients) client.clear()
  clients.clear()
}
