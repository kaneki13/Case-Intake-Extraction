import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { AppProviders } from '../src/App'
import { routes } from '../src/routes'
import { createTestQueryClient } from './queryClients'

export { createTestQueryClient } from './queryClients'

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
