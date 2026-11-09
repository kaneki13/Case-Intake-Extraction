import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { delay, http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocumentDetail, DuplicateGroup } from '../src/api/types'
import { comparisonClass } from '../src/lib/compare'
import {
  D01, catalogFixture, duplicateChangedFieldId, duplicateDocFixtures, duplicateFixtures, duplicatesOf,
} from './fixtures'
import { problem, served } from './msw/handlers'
import { server } from './msw/server'
import { renderApp } from './renderApp'

const [DUP_A, DUP_B] = duplicateDocFixtures as [DocumentDetail, DocumentDetail]
const firstLine = (text: string | null) => (text ?? '').split('\n').find((l) => l.trim() !== '')?.trim() ?? ''
const labelOf = (id: string) => catalogFixture.fields.find((f) => f.id === id)?.label as string
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T

const scrollIntoView = vi.fn()
beforeEach(() => {
  scrollIntoView.mockClear()
  Element.prototype.scrollIntoView = scrollIntoView
})
afterEach(() => {
  vi.restoreAllMocks()
})

const table = () => screen.findByRole('table', { name: 'Field comparison' })
const rowOf = (t: HTMLElement, fieldId: string) =>
  within(t).getByRole('button', { name: labelOf(fieldId) }).closest('tr') as HTMLElement
const text = (id: string) => screen.findByRole('article', { name: `Text of ${id}` })

/** An integer field the duplicate fixture extracts (never by name). */
function integerPick(): string {
  const pick = catalogFixture.fields.find((f) => {
    const v = DUP_A.extraction.cases[0]?.fields[f.id]
    return f.type === 'integer' && v?.status === 'extracted' && typeof v.value === 'number'
  })
  if (!pick) throw new Error('the duplicate fixture extracts no integer field')
  return pick.id
}

/** Serve DUP_B with the integer field `id` moved by `by`. */
function serveShifted(id: string, by: number) {
  const b = clone(DUP_B)
  const field = b.extraction.cases[0]?.fields[id]
  if (!field) throw new Error('fixture field missing')
  field.value = (field.value as number) + by
  serve(b)
}

/** Serve a changed copy of a duplicate document. */
function serve(doc: DocumentDetail) {
  server.use(http.get(`*/api/documents/${doc.id}`, () => HttpResponse.json(served(doc))))
}

describe('DuplicatesPage', () => {
  it('duplicatesListShowsGroupsWithReasonAndConfidence', async () => {
    renderApp('/duplicates')
    const main = screen.getByRole('main')
    expect(await within(main).findByRole('heading', { level: 1, name: 'Possible duplicates' })).toBeInTheDocument()
    const link = await within(main).findByRole('link', { name: 'DUP_A and DUP_B' })
    expect(link).toHaveAttribute('href', '/duplicates/0')
    const item = link.closest('li') as HTMLElement
    expect(within(item).getByRole('img', { name: 'High confidence' })).toBeInTheDocument()
    expect(within(item).getByText('Matching identity and event values.')).toBeInTheDocument()
  })

  it('noGroupsShowsIntentionalEmptyState', async () => {
    server.use(http.get('*/api/duplicates', () => HttpResponse.json(duplicatesOf([]))))
    renderApp('/duplicates')
    expect(await screen.findByRole('heading', { name: 'No duplicate cases found' })).toBeInTheDocument()
    expect(within(screen.getByRole('main')).queryByRole('link')).toBeNull()
  })

  it('duplicatesErrorShowsMessageAndRetry', async () => {
    server.use(http.get('*/api/duplicates', () => problem(500, 'Duplicate store unavailable'), { once: true }))
    const { user } = renderApp('/duplicates')
    const alert = await within(screen.getByRole('main')).findByRole('alert')
    expect(alert).toHaveTextContent('Duplicate store unavailable')
    await user.click(within(alert).getByRole('button', { name: /retry/i }))
    expect(await screen.findByRole('link', { name: 'DUP_A and DUP_B' })).toBeInTheDocument()
  })
})

describe('DuplicateCompare', () => {
  it('compareViewShowsBothTextsAndAllCatalogFields', async () => {
    renderApp('/duplicates/0')
    expect(await text('DUP_A')).toHaveTextContent(firstLine(DUP_A.text))
    expect(await text('DUP_B')).toHaveTextContent(firstLine(DUP_B.text))
    const t = await table()
    for (const field of catalogFixture.fields) {
      expect(within(t).getByRole('button', { name: field.label })).toBeInTheDocument()
    }
    // Sections in catalog order, as row-group headers.
    const sections = within(t).getAllByRole('rowheader').filter((h) => h.hasAttribute('colspan'))
    expect(sections.map((h) => h.textContent)).toEqual(catalogFixture.sections.map((s) => s.label))
  })

  it('matchRowsGreenDifferRowsRedWithIconAndText', async () => {
    renderApp('/duplicates/0')
    const t = await table()
    const differ = rowOf(t, duplicateChangedFieldId)
    expect(differ).toHaveClass(comparisonClass('differ'))
    expect(within(differ).getByText('✗')).toBeInTheDocument()
    // The state word is visible next to the icon, not only announced.
    expect(within(differ).getByText('Differ')).not.toHaveClass('visually-hidden')

    const others = catalogFixture.fields.filter((f) => f.id !== duplicateChangedFieldId)
    for (const field of others) {
      const row = rowOf(t, field.id)
      expect(row).toHaveClass(comparisonClass('match'))
      expect(row).not.toHaveClass(comparisonClass('differ'))
      expect(within(row).getByText('✓')).toBeInTheDocument()
      expect(within(row).getByText('Match')).not.toHaveClass('visually-hidden')
    }
    expect(screen.getByText(`${others.length} match · 1 differ · 0 one-sided`)).toBeInTheDocument()
  })

  it('oneSidedAndBothAbsentRowsHaveTheirOwnClassIconAndText', async () => {
    const [oneSided, absent] = catalogFixture.fields.filter((f) => f.id !== duplicateChangedFieldId).map((f) => f.id) as [string, string]
    const a = clone(DUP_A)
    const b = clone(DUP_B)
    const caseA = a.extraction.cases[0]
    const caseB = b.extraction.cases[0]
    if (!caseA || !caseB) throw new Error('fixture case missing')
    caseB.fields[oneSided] = { status: 'unknown' }
    caseA.fields[absent] = { status: 'unknown' }
    delete caseB.fields[absent]
    serve(a)
    serve(b)
    renderApp('/duplicates/0')
    const t = await table()
    await waitFor(() => expect(rowOf(t, oneSided)).toHaveClass(comparisonClass('one-sided')))
    expect(within(rowOf(t, oneSided)).getByText('◌')).toBeInTheDocument()
    expect(within(rowOf(t, oneSided)).getByText('One side only')).not.toHaveClass('visually-hidden')
    expect(rowOf(t, absent)).toHaveClass(comparisonClass('both-absent'))
    expect(within(rowOf(t, absent)).getByText('—')).toBeInTheDocument()
    expect(within(rowOf(t, absent)).queryByText('·')).toBeNull()
    expect(within(rowOf(t, absent)).getByText('Neither')).not.toHaveClass('visually-hidden')
    const matches = catalogFixture.fields.length - 3
    expect(screen.getByText(`${matches} match · 1 differ · 1 one-sided · 1 in neither`)).toBeInTheDocument()
  })

  it('clickingRowHighlightsQuoteInBothTexts', async () => {
    const id = duplicateChangedFieldId
    const { user, router } = renderApp('/duplicates/0')
    const t = await table()
    const [a, b] = [await text('DUP_A'), await text('DUP_B')]
    await waitFor(() => expect(a.querySelectorAll('.quote').length).toBeGreaterThan(0))
    expect(a.querySelector('.is-selected')).toBeNull()

    const button = within(t).getByRole('button', { name: labelOf(id) })
    await user.click(button)
    expect(new URLSearchParams(router.state.location.search).get('field')).toBe(id)
    expect(button).toHaveAttribute('aria-pressed', 'true')
    for (const pane of [a, b]) {
      const selected = Array.from(pane.querySelectorAll<HTMLElement>('.quote.is-selected'))
      expect(selected.length).toBeGreaterThan(0)
      for (const s of selected) expect(s.dataset.fields?.split(' ')).toContain(id)
      expect(scrollIntoView.mock.contexts).toContain(selected[0])
    }

    // Clicking the selected row again clears the selection in both texts.
    await user.click(button)
    await waitFor(() => expect(a.querySelector('.is-selected')).toBeNull())
    expect(b.querySelector('.is-selected')).toBeNull()
  })

  it('clickingAnyCellOfARowSelectsItsField', async () => {
    const id = duplicateChangedFieldId
    const { user, router } = renderApp('/duplicates/0')
    const t = await table()
    const cells = () => rowOf(t, id).querySelectorAll('td')
    const field = () => new URLSearchParams(router.state.location.search).get('field')
    expect(cells().length).toBe(3)

    // A value cell, not the label button: the row's own handler selects the field.
    await user.click(cells()[2] as HTMLElement)
    expect(field()).toBe(id)
    expect(within(t).getByRole('button', { name: labelOf(id) })).toHaveAttribute('aria-pressed', 'true')
    // The state cell toggles it off again.
    await user.click(cells()[1] as HTMLElement)
    expect(field()).toBeNull()
    // The label button still works from the keyboard, and one click toggles once (not twice).
    within(t).getByRole('button', { name: labelOf(id) }).focus()
    await user.keyboard('{Enter}')
    expect(field()).toBe(id)
    fireEvent.click(within(t).getByRole('button', { name: labelOf(id) }))
    expect(field()).toBeNull()
  })

  it('integerToleranceComesFromTheApi', async () => {
    const pick = integerPick()
    serveShifted(pick, 3)
    // The server says integers within 5 agree: 3 apart is a match, with no frontend constant in the way.
    server.use(http.get('*/api/duplicates', () => HttpResponse.json(duplicatesOf(duplicateFixtures, 5))))
    renderApp('/duplicates/0')
    const t = await table()
    await waitFor(() => expect(rowOf(t, pick)).toHaveClass(comparisonClass('match')))
  })

  it('integerOutsideTheApiToleranceDiffers', async () => {
    const pick = integerPick()
    serveShifted(pick, 3)
    server.use(http.get('*/api/duplicates', () => HttpResponse.json(duplicatesOf(duplicateFixtures, 2))))
    renderApp('/duplicates/0')
    const t = await table()
    await waitFor(() => expect(rowOf(t, pick)).toHaveClass(comparisonClass('differ')))
  })

  it('clickingAQuoteSelectsTheFieldInBothTexts', async () => {
    const { user } = renderApp('/duplicates/0')
    const t = await table()
    const [a, b] = [await text('DUP_A'), await text('DUP_B')]
    await waitFor(() => expect(a.querySelectorAll('.quote').length).toBeGreaterThan(0))
    const quote = a.querySelector<HTMLElement>('.quote') as HTMLElement
    const id = quote.dataset.fields?.split(' ')[0] as string
    await user.click(quote)
    await waitFor(() => expect(b.querySelector(`.quote.is-selected[data-fields~="${id}"]`)).not.toBeNull())
    expect(within(t).getByRole('button', { name: labelOf(id) })).toHaveAttribute('aria-pressed', 'true')
  })

  it('paneWithoutQuoteShowsNote', async () => {
    const id = duplicateChangedFieldId
    const b = clone(DUP_B)
    const field = b.extraction.cases[0]?.fields[id]
    if (!field) throw new Error('fixture field missing')
    delete field.provenance
    serve(b)
    const { user } = renderApp('/duplicates/0')
    const t = await table()
    await text('DUP_B')
    await user.click(within(t).getByRole('button', { name: labelOf(id) }))
    const paneB = screen.getByRole('region', { name: 'Document B: DUP_B' })
    expect(await within(paneB).findByText('No quote in this document')).toBeInTheDocument()
    const paneA = screen.getByRole('region', { name: 'Document A: DUP_A' })
    expect(within(paneA).queryByText('No quote in this document')).toBeNull()
  })

  it('groupOfThreeOffersAPairSelector', async () => {
    const group: DuplicateGroup = {
      documentIds: ['D01', 'DUP_A', 'DUP_B'], confidence: 'medium', reason: 'Three reports.',
      pairs: [
        { a: { id: 'D01', caseIndex: 0 }, b: { id: 'DUP_A', caseIndex: 0 }, agreements: 16 },
        { a: { id: 'D01', caseIndex: 0 }, b: { id: 'DUP_B', caseIndex: 0 }, agreements: 15 },
        { a: { id: 'DUP_A', caseIndex: 0 }, b: { id: 'DUP_B', caseIndex: 0 }, agreements: 15 },
      ],
    }
    server.use(http.get('*/api/duplicates', () => HttpResponse.json(duplicatesOf([group]))))
    const { user } = renderApp('/duplicates/0')
    const tabs = await screen.findByRole('tablist', { name: 'Document pairs' })
    expect(within(tabs).getAllByRole('tab')).toHaveLength(3)
    expect(await text('D01')).toHaveTextContent(firstLine(D01.text))
    expect(await text('DUP_A')).toBeInTheDocument()

    await user.click(within(tabs).getByRole('tab', { name: 'D01 and DUP_B' }))
    expect(await text('DUP_B')).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Text of DUP_A' })).toBeNull()
  })

  it('unknownGroupShowsNotFound', async () => {
    renderApp('/duplicates/7')
    expect(await screen.findByRole('heading', { name: 'Duplicate group not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /all duplicates/i })).toHaveAttribute('href', '/duplicates')
  })
})

describe('DocumentRail duplicates link', () => {
  it('railLinksToDuplicates', async () => {
    const { user, router } = renderApp('/documents/D01')
    const nav = screen.getByRole('navigation', { name: 'Duplicate review' })
    const link = await within(nav).findByRole('link', { name: `Duplicates (${duplicateFixtures.length})` })
    expect(link).toHaveAttribute('href', '/duplicates')
    await user.click(link)
    expect(router.state.location.pathname).toBe('/duplicates')
    expect(link).toHaveAttribute('aria-current', 'page')
  })

  it('railHidesTheCountWhileLoading', async () => {
    server.use(http.get('*/api/duplicates', async () => { await delay('infinite') }))
    renderApp('/documents/D01')
    const nav = screen.getByRole('navigation', { name: 'Duplicate review' })
    expect(within(nav).getByRole('link', { name: 'Duplicates' })).toHaveAttribute('href', '/duplicates')
  })

  it('railShowsWhenDuplicatesFailToLoad', async () => {
    server.use(http.get('*/api/duplicates', () => problem(500, 'boom')))
    renderApp('/documents/D01')
    const nav = screen.getByRole('navigation', { name: 'Duplicate review' })
    expect(await within(nav).findByRole('link', { name: "Duplicates (couldn't load)" })).toHaveAttribute('href', '/duplicates')
  })
})
