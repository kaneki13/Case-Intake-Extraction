import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { AppProviders } from '../src/App'
import { routes } from '../src/routes'

/** A fresh client per test: no retries, no background refetch, no gc timers. */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity, gcTime: Infinity, refetchOnWindowFocus: false },
    },
  })
}

/** The three landmarks of the shell, looked up lazily (they appear after the first render). */
export const panes = {
  rail: () => screen.getByRole('navigation', { name: 'Documents' }),
  document: () => screen.getByRole('main'),
  fields: () => screen.getByRole('complementary', { name: 'Extracted fields' }),
}

/** Render the whole app (real routes, real providers) at `path` against the MSW handlers. */
export function renderApp(path = '/') {
  const queryClient = createTestQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const user = userEvent.setup()
  const utils = render(
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { ...utils, router, queryClient, user, panes }
}
