import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { AppProviders } from '../src/App'
import { useQuoteModel } from '../src/hooks/useQuoteModel'
import type { DocumentDetail } from '../src/api/types'
import { D01, catalogFixture } from './fixtures'
import { server } from './msw/server'
import { createTestQueryClient } from './renderApp'

function Probe() {
  const m = useQuoteModel()
  return <output data-testid="probe">{JSON.stringify([...m.invalidByField.entries()])}</output>
}

describe('useQuoteModel', () => {
  it('exposesInvalidSpansByField', async () => {
    const doc = JSON.parse(JSON.stringify(D01)) as DocumentDetail
    doc.text = 'short'
    const id = catalogFixture.fields.find((f) => doc.extraction.cases[0]?.fields[f.id]?.provenance)?.id as string
    const f = doc.extraction.cases[0]?.fields[id]
    if (!f) throw new Error('no field')
    f.provenance = [{ quote: 'x', start: 2, end: 99 }]
    server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
    const router = createMemoryRouter([{ path: '/documents/:id', element: <Probe /> }], {
      initialEntries: ['/documents/D01'],
    })
    render(
      <AppProviders queryClient={createTestQueryClient()}>
        <RouterProvider router={router} />
      </AppProviders>,
    )
    const out = await screen.findByText(new RegExp(id))
    expect(JSON.parse(out.textContent ?? '[]')).toContainEqual([id, [{ start: 2, end: 99 }]])
  })
})
