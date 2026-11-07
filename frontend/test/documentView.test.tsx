import { act, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocumentDetail } from '../src/api/types'
import { D01, catalogFixture } from './fixtures'
import { server } from './msw/server'
import { renderApp } from './renderApp'

/** The catalog field whose provenance starts/ends at the given code-point offsets (looked up from the fixture). */
function fieldAt(start: number, end: number): string {
  const fields = D01.extraction.cases[0]?.fields ?? {}
  const hit = catalogFixture.fields.find((f) =>
    fields[f.id]?.provenance?.some((p) => p.start === start && p.end === end),
  )
  if (!hit) throw new Error(`no fixture field with provenance (${start}, ${end})`)
  return hit.id
}

/** D01 with the quoted field's provenance replaced by `provenance`. */
function withProvenance(text: string, fieldId: string, provenance: { quote: string; start: number; end: number }[]): DocumentDetail {
  const doc = JSON.parse(JSON.stringify(D01)) as DocumentDetail
  doc.text = text
  const field = doc.extraction.cases[0]?.fields[fieldId]
  if (!field) throw new Error('fixture field missing')
  field.provenance = provenance
  // keep every other field's quotes out of the way
  for (const [id, f] of Object.entries(doc.extraction.cases[0]?.fields ?? {})) if (id !== fieldId) delete f.provenance
  return doc
}

const scrollIntoView = vi.fn()
beforeEach(() => {
  scrollIntoView.mockClear()
  Element.prototype.scrollIntoView = scrollIntoView
})
afterEach(() => {
  vi.restoreAllMocks()
})

const article = () => screen.findByRole('article', { name: 'Document text' })

describe('DocumentView text', () => {
  it('selectingFieldHighlightsExactQuoteText', async () => {
    const id = fieldAt(276, 284)
    renderApp(`/documents/D01?field=${id}`)
    const text = await article()
    await waitFor(() => expect(text.querySelectorAll('.is-selected').length).toBeGreaterThan(0))
    const selected = Array.from(text.querySelectorAll('.is-selected')).map((e) => e.textContent).join('')
    expect(selected).toBe('Mrs M.K.')
    expect(text.querySelector('.is-selected')).toHaveAttribute('aria-current', 'true')
    expect(screen.getAllByText('selected quote').length).toBeGreaterThan(0)
  })

  it('selectingFieldCallsScrollIntoView', async () => {
    const id = fieldAt(276, 284)
    renderApp(`/documents/D01?field=${id}`)
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1))
    expect(scrollIntoView).toHaveBeenCalledWith(expect.objectContaining({ block: 'center' }))
  })

  it('allQuotesGetFaintUnderline', async () => {
    renderApp('/documents/D01')
    const text = await article()
    const fields = D01.extraction.cases[0]?.fields ?? {}
    const ranges = new Set(Object.values(fields).flatMap((f) => (f.provenance ?? []).map((p) => `${p.start}-${p.end}`)))
    expect(ranges.size).toBeGreaterThan(0)
    await waitFor(() => expect(text.querySelectorAll('.quote').length).toBe(ranges.size))
    expect(text.querySelectorAll('.is-selected').length).toBe(0)
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('clickingQuoteSelectsItsField', async () => {
    const { user, router } = renderApp('/documents/D01')
    const text = await article()
    await waitFor(() => expect(text.querySelectorAll('.quote').length).toBeGreaterThan(0))
    const quote = text.querySelector('.quote') as HTMLElement
    const first = (quote.dataset.fields ?? '').split(' ')[0] as string
    await user.click(quote)
    expect(new URLSearchParams(router.state.location.search).get('field')).toBe(first)
    await waitFor(() => expect(text.querySelectorAll('.is-selected').length).toBeGreaterThan(0))
  })

  it('outOfRangeSpanShowsWarning', async () => {
    const id = fieldAt(276, 284)
    const doc = withProvenance('short text', id, [{ quote: 'x', start: 5, end: 500 }])
    server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    renderApp(`/documents/D01?field=${id}`)
    const warning = await screen.findByRole('status', { name: /quote position/i })
    expect(warning).toHaveTextContent('1')
    expect(await article()).toHaveTextContent('short text')
    expect(err).toHaveBeenCalledTimes(1)
  })

  it('astralCharactersBeforeQuoteStillHighlightExactly', async () => {
    const id = fieldAt(276, 284)
    // the emoji is 1 code point but 2 UTF-16 units; the quote starts at code point 11
    const doc = withProvenance('😀 Patient: Mrs M.K. took it', id, [{ quote: 'Mrs M.K.', start: 11, end: 19 }])
    server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
    renderApp(`/documents/D01?field=${id}`)
    const text = await article()
    await waitFor(() => expect(text.querySelector('.is-selected')).not.toBeNull())
    expect(text.querySelector('.is-selected')?.textContent).toBe('Mrs M.K.')
  })

  it('unknownFieldParamSelectsNothingAndLogsOnce', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    renderApp('/documents/D01?field=not_a_field')
    const text = await article()
    await waitFor(() => expect(text.querySelectorAll('.quote').length).toBeGreaterThan(0))
    expect(text.querySelectorAll('.is-selected').length).toBe(0)
    expect(err).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('selected quote')).toBeNull()
  })

  it('clickingAgainCyclesThroughSegmentFields', async () => {
    const [a, b] = catalogFixture.fields
      .map((f) => f.id)
      .filter((id) => id in (D01.extraction.cases[0]?.fields ?? {}))
    if (a === undefined || b === undefined) throw new Error('fixture needs two fields')
    const doc = withProvenance('Patient Mrs M.K. took it', a, [{ quote: 'Mrs M.K.', start: 8, end: 16 }])
    const second = doc.extraction.cases[0]?.fields[b]
    if (!second) throw new Error('missing field')
    second.provenance = [{ quote: 'Mrs M.K.', start: 8, end: 16 }]
    server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
    const { user, router } = renderApp('/documents/D01')
    const text = await article()
    await waitFor(() => expect(text.querySelector('.quote')).not.toBeNull())
    const field = () => new URLSearchParams(router.state.location.search).get('field')
    await user.click(text.querySelector('.quote') as HTMLElement)
    expect(field()).toBe(a)
    await user.click(text.querySelector('.quote') as HTMLElement)
    expect(field()).toBe(b)
    await user.click(text.querySelector('.quote') as HTMLElement)
    expect(field()).toBe(a)
  })

  it('clickingALaterQuoteOfAFieldDoesNotScrollToItsFirstQuote', async () => {
    const id = fieldAt(276, 284)
    const doc = withProvenance('Alpha one. Beta two. Gamma three.', id, [
      { quote: 'Alpha', start: 0, end: 5 },
      { quote: 'Gamma', start: 21, end: 26 },
    ])
    server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
    const { user, router } = renderApp('/documents/D01')
    const text = await article()
    await waitFor(() => expect(text.querySelectorAll('.quote')).toHaveLength(2))
    const [first, second] = Array.from(text.querySelectorAll<HTMLElement>('.quote'))
    await user.click(second as HTMLElement)
    expect(new URLSearchParams(router.state.location.search).get('field')).toBe(id)
    await waitFor(() => expect(second).toHaveClass('is-selected'))
    // The reviewer is looking at the quote they clicked: the text must not jump to the first one.
    expect(scrollIntoView.mock.contexts).not.toContain(first)

    // The click marker is used once: a later selection change scrolls as usual.
    await act(async () => {
      await router.navigate('/documents/D01', { replace: true })
    })
    await act(async () => {
      await router.navigate(`/documents/D01?field=${id}`, { replace: true })
    })
    await waitFor(() => expect(scrollIntoView.mock.contexts).toContain(first))
  })

  it('noRescrollOnUnrelatedRerender', async () => {
    const id = fieldAt(276, 284)
    const { router } = renderApp(`/documents/D01?field=${id}`)
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1))
    await act(async () => {
      await router.navigate(`/documents/D01?field=${id}&unrelated=1`, { replace: true })
    })
    expect(scrollIntoView).toHaveBeenCalledTimes(1)
  })
})
