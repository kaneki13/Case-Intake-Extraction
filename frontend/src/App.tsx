import { useState, type ReactNode } from 'react'
import * as Tooltip from '@radix-ui/react-tooltip'
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { createQueryClient } from './api/queries'
import { routes } from './routes'

/** Everything the screen needs above the router. Tests wrap a memory router in the same providers. */
export function AppProviders({ queryClient, children }: { queryClient: QueryClient; children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <Tooltip.Provider delayDuration={300} skipDelayDuration={150}>
        {children}
      </Tooltip.Provider>
    </QueryClientProvider>
  )
}

export default function App() {
  const [queryClient] = useState(createQueryClient)
  const [router] = useState(() => createBrowserRouter(routes))
  return (
    <AppProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>
  )
}
