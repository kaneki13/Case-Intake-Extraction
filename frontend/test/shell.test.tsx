import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { delay, http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Catalog, DocumentDetail, DocumentSummary } from '../src/api/types'
import { useSelection } from '../src/hooks/useSelection'
import { D01, NC1, catalogFixture, summaryFixtures } from './fixtures'
import { problem } from './msw/handlers'
import { server } from './msw/server'
import { createTestQueryClient, panes, renderApp } from './renderApp'

const firstLine = (text: string | null) => (text ?? '').split('\n').find((l) => l.trim() !== '')?.trim() ?? ''

afterEach(() => {
  vi.restoreAllMocks()
  document.documentElement.removeAttribute('data-theme')
  try {
    localStorage.clear()
  } catch {
    // storage unavailable: nothing to clear
  }
})

describe('DocumentRail', () => {
  it('railListsDocumentsWithCaseCountFlagsAndRisk', async () => {
    renderApp('/documents/D01')
    const rail = panes.rail()
    const links = await within(rail).findAllByRole('link')
    expect(links).toHaveLength(summaryFixtures.length)

    const link = (id: string) => {
      const found = links.find((l) => l.getAttribute('href') === `/documents/${id}`)
      if (!found) throw new Error(`no rail link for ${id}`)
      return found
    }

    // D01: one case, one medium value -> one risky value.
    expect(within(link('D01')).getByText('1 case')).toBeInTheDocument()
    expect(within(link('D01')).getByRole('img', { name: '1 risky value' })).toBeInTheDocument()

    // MP1: two cases, a multiple-patients flag, conflict 1 + medium 2 + low 0 = 3.
    expect(within(link('MP1')).getByText('2 cases')).toBeInTheDocument()
    expect(within(link('MP1')).getByRole('img', { name: 'Multiple patients' })).toBeInTheDocument()
    expect(within(link('MP1')).getByRole('img', { name: '3 risky values' })).toBeInTheDocument()

    // No-case documents: "No case", their flag, nothing risky.
    expect(within(link('NC1')).getByText('No case')).toBeInTheDocument()
    expect(within(link('NC1')).getByRole('img', { name: 'No adverse event reported' })).toBeInTheDocument()
    expect(within(link('NC1')).getByRole('img', { name: 'No risky values' })).toBeInTheDocument()
    expect(within(link('PDF1')).getByRole('img', { name: 'Not processed — unsupported format' })).toBeInTheDocument()

    // Titles are one line; the full title is kept for the tooltip.
    expect(within(link('MP1')).getByTitle('MP1')).toBeInTheDocument()
  })

  it('riskCountRanksLevelsByCatalogOrderNotByName', async () => {
    // Same names, reversed order: "high" is now the weakest level and must count as risky.
    const catalog: Catalog = { ...catalogFixture, confidence_levels: ['low', 'medium', 'high'] }
    const summary: DocumentSummary = {
      id: 'R1', title: 'Ranked', caseCount: 1, flags: [], progress: { reviewed: 0, total: 16 },
      reviewCounts: { conflict: 1, unknown: 9, byConfidence: { low: 4, medium: 5, high: 2 } },
    }
    server.use(
      http.get('*/api/catalog', () => HttpResponse.json(catalog)),
      http.get('*/api/documents', () => HttpResponse.json([summary])),
      http.get('*/api/documents/R1', () => HttpResponse.json({ ...D01, id: 'R1', title: 'Ranked' })),
    )
    renderApp('/documents/R1')
    // conflict 1 + medium 5 + high 2 = 8 (name-based counting would give 10).
    expect(await within(panes.rail()).findByRole('img', { name: '8 risky values' })).toBeInTheDocument()
  })

  it('firstDocumentSelectedByDefault', async () => {
    const { router } = renderApp('/')
    await waitFor(() => expect(router.state.location.pathname).toBe('/documents/D01'))
    const rail = panes.rail()
    const active = await within(rail).findByRole('link', { current: 'page' })
    expect(active).toHaveAttribute('href', '/documents/D01')
    for (const l of within(rail).getAllByRole('link')) {
      if (l !== active) expect(l).not.toHaveAttribute('aria-current')
    }
  })

  it('railLinkNavigatesToTheDocument', async () => {
    const { router, user } = renderApp('/documents/D01')
    const rail = panes.rail()
    const mp1 = (await within(rail).findAllByRole('link')).find((l) => l.getAttribute('href') === '/documents/MP1')
    if (!mp1) throw new Error('no MP1 link')
    await user.click(mp1)
    await waitFor(() => expect(router.state.location.pathname).toBe('/documents/MP1'))
    expect(router.state.historyAction).toBe('PUSH')
    await waitFor(() => expect(mp1).toHaveAttribute('aria-current', 'page'))
  })
})

describe('states', () => {
  it('loadingShowsSkeletons', async () => {
    server.use(
      http.get('*/api/catalog', async () => { await delay('infinite') }),
      http.get('*/api/documents', async () => { await delay('infinite') }),
      http.get('*/api/documents/:id', async () => { await delay('infinite') }),
    )
    renderApp('/documents/D01')
    for (const pane of [panes.rail(), panes.document(), panes.fields()]) {
      const status = within(pane).getByRole('status')
      expect(status).toHaveAttribute('aria-busy', 'true')
      expect(status).toHaveAccessibleName(/loading/i)
    }
  })

  it('errorShowsMessageAndRetryRefetches', async () => {
    let calls = 0
    server.use(
      http.get(
        '*/api/documents/D01',
        () => {
          calls++
          return problem(500, 'Extraction store unavailable')
        },
        { once: true },
      ),
    )
    const { user } = renderApp('/documents/D01')
    const main = panes.document()
    const alert = await within(main).findByRole('alert')
    expect(alert).toHaveTextContent('Extraction store unavailable')
    expect(calls).toBe(1)

    await user.click(within(alert).getByRole('button', { name: /retry/i }))
    const article = await within(main).findByRole('article', { name: 'Document text' })
    expect(article).toHaveTextContent(firstLine(D01.text))
    expect(within(main).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('railErrorShowsMessageAndRetryRefetches', async () => {
    server.use(http.get('*/api/documents', () => problem(503, 'Document store offline'), { once: true }))
    const { user } = renderApp('/documents/D01')
    const rail = panes.rail()
    const alert = await within(rail).findByRole('alert')
    expect(alert).toHaveTextContent('Document store offline')
    await user.click(within(alert).getByRole('button', { name: /retry/i }))
    expect(await within(rail).findAllByRole('link')).toHaveLength(summaryFixtures.length)
  })

  it('badResponseShapeShowsTheProblemWithoutAnHttpStatus', async () => {
    server.use(http.get('*/api/documents', () => HttpResponse.json({ nope: true })))
    renderApp('/documents/D01')
    const alert = await within(panes.rail()).findByRole('alert')
    expect(alert).toHaveTextContent('Unexpected response shape: document list is not an array')
    expect(alert).not.toHaveTextContent(/HTTP/)
  })

  it('documentChangeResetsBothPanesToTheTop', async () => {
    const { user } = renderApp('/documents/D01')
    const fields = panes.fields()
    await within(fields).findAllByRole('region')
    const main = panes.document()
    const handle = within(fields).getByRole('button', { name: /hide fields/i })
    const body = document.getElementById(handle.getAttribute('aria-controls') ?? '') as HTMLElement
    main.scrollTop = 400
    body.scrollTop = 300
    const mp1 = within(panes.rail()).getAllByRole('link').find((l) => l.getAttribute('href') === '/documents/MP1')
    if (!mp1) throw new Error('no MP1 link')
    await user.click(mp1)
    await waitFor(() => expect(within(fields).queryAllByRole('tab')).toHaveLength(2))
    expect(main.scrollTop).toBe(0)
    expect(body.scrollTop).toBe(0)
  })

  it('documentChangeWithAFieldLeavesTheScrollToTheQuote', async () => {
    const id = catalogFixture.fields.find((f) => (D01.extraction.cases[0]?.fields[f.id]?.provenance?.length ?? 0) > 0)?.id
    if (!id) throw new Error('D01 has no quoted field')
    const { router } = renderApp('/documents/MP1')
    const fields = panes.fields()
    await waitFor(() => expect(within(fields).queryAllByRole('tab')).toHaveLength(2))
    const main = panes.document()
    main.scrollTop = 400
    // E.g. Back to a document with a field selected: its quote scroll must not be undone by a reset to the top.
    await act(async () => {
      await router.navigate(`/documents/D01?field=${id}`)
    })
    await within(main).findByRole('article', { name: 'Document text' })
    expect(main.scrollTop).toBe(400)
  })

  it('noCaseDocumentShowsReasonFromFlags', async () => {
    renderApp('/documents/NC1')
    const fields = panes.fields()
    expect(await within(fields).findByRole('heading', { name: 'No case to review' })).toBeInTheDocument()
    expect(within(fields).getByText('No adverse event reported')).toBeInTheDocument()
    expect(within(fields).queryByRole('alert')).not.toBeInTheDocument()
    // The document text is still there to read.
    expect(await within(panes.document()).findByRole('article', { name: 'Document text' })).toHaveTextContent(
      firstLine(NC1.text),
    )
  })

  it('noCaseUnknownFlagShownAsNeutralChip', async () => {
    const doc: DocumentDetail = {
      ...NC1,
      extraction: { ...NC1.extraction, flags: ['missing_minimum_elements', 'reviewer_hold'] },
    }
    server.use(http.get('*/api/documents/NC1', () => HttpResponse.json(doc)))
    renderApp('/documents/NC1')
    const fields = panes.fields()
    expect(await within(fields).findByText('Minimum case elements missing')).toBeInTheDocument()
    expect(within(fields).getByText('reviewer_hold')).toBeInTheDocument()
  })

  it('noCaseWithoutFlagsSaysNoReasonGiven', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const doc: DocumentDetail = { ...NC1, extraction: { ...NC1.extraction, flags: [] } }
    server.use(http.get('*/api/documents/NC1', () => HttpResponse.json(doc)))
    renderApp('/documents/NC1')
    const fields = panes.fields()
    expect(await within(fields).findByRole('heading', { name: 'No case to review' })).toBeInTheDocument()
    expect(within(fields).getByText(/no reason/i)).toBeInTheDocument()
    expect(error).toHaveBeenCalled()
  })

  it('nullTextShowsNoTextLayer', async () => {
    renderApp('/documents/PDF1')
    const main = panes.document()
    expect(await within(main).findByRole('heading', { name: 'No text layer' })).toBeInTheDocument()
    expect(within(main).getByText('Not processed — unsupported format')).toBeInTheDocument()
    expect(within(main).queryByRole('article', { name: 'Document text' })).not.toBeInTheDocument()
    const fields = panes.fields()
    expect(await within(fields).findByText('Not processed — unsupported format')).toBeInTheDocument()
  })
})

describe('shell chrome', () => {
  it('themeToggleSetsDataThemeAndPersists', async () => {
    const { user } = renderApp('/documents/D01')
    const banner = screen.getByRole('banner')
    const toggle = within(banner).getByRole('button', { name: /switch to (dark|light) theme/i })
    const target = /dark/i.test(toggle.getAttribute('aria-label') ?? '') ? 'dark' : 'light'
    await user.click(toggle)
    expect(document.documentElement).toHaveAttribute('data-theme', target)
    expect(localStorage.getItem('theme')).toBe(target)
  })
})

describe('useSelection', () => {
  function Probe() {
    const s = useSelection()
    return (
      <div>
        <output aria-label="selection">{JSON.stringify({ docId: s.docId, caseIndex: s.caseIndex, fieldId: s.fieldId })}</output>
        <button onClick={() => s.select('f-one')}>select one</button>
        <button onClick={() => s.select(null)}>clear</button>
        <button onClick={() => s.setCase(1)}>case two</button>
      </div>
    )
  }

  function renderProbe(path: string) {
    const router = createMemoryRouter([{ path: '/documents/:id', element: <Probe /> }], { initialEntries: [path] })
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    const read = () =>
      JSON.parse(screen.getByRole('status', { name: 'selection' }).textContent ?? '{}') as {
        docId: string; caseIndex: number; fieldId: string | null
      }
    return { router, read, user: userEvent.setup() }
  }

  it('readsDocCaseAndFieldFromTheUrl', async () => {
    const { read } = renderProbe('/documents/MP1?case=1&field=f-one')
    await waitFor(() => expect(read()).toEqual({ docId: 'MP1', caseIndex: 1, fieldId: 'f-one' }))
  })

  it('outOfRangeCaseFallsBackToFirstWithError', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { read } = renderProbe('/documents/MP1?case=7')
    await waitFor(() => expect(error).toHaveBeenCalled())
    expect(read().caseIndex).toBe(0)
  })

  it('malformedCaseFallsBackToFirstWithError', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { read } = renderProbe('/documents/MP1?case=abc')
    expect(read().caseIndex).toBe(0)
    await waitFor(() => expect(error).toHaveBeenCalled())
  })

  it('fieldChangesReplaceHistoryCaseChangesPush', async () => {
    const { router, read, user } = renderProbe('/documents/MP1')
    await user.click(screen.getByRole('button', { name: 'select one' }))
    expect(read().fieldId).toBe('f-one')
    expect(router.state.historyAction).toBe('REPLACE')

    await user.click(screen.getByRole('button', { name: 'case two' }))
    await waitFor(() => expect(read().caseIndex).toBe(1))
    expect(router.state.historyAction).toBe('PUSH')
    expect(read().fieldId).toBe('f-one')

    await user.click(screen.getByRole('button', { name: 'clear' }))
    expect(read().fieldId).toBeNull()
    expect(router.state.location.search).not.toContain('field=')
  })
})
