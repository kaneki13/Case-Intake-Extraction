import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FieldResult } from '../src/api/types'
import { isRisky } from '../src/lib/risk'
import { D01, catalogFixture } from './fixtures'
import { server } from './msw/server'
import { panes, renderApp } from './renderApp'

const levels = catalogFixture.confidence_levels
const d01Fields = (): Record<string, FieldResult> => D01.extraction.cases[0]?.fields ?? {}
/** Catalog field ids in display order: catalog sections, then catalog field order inside each. Never by name. */
const displayOrder = () =>
  catalogFixture.sections.flatMap((s) => catalogFixture.fields.filter((f) => f.section === s.id)).map((f) => f.id)
const labelOf = (id: string): string => catalogFixture.fields.find((f) => f.id === id)?.label as string

const rowsIn = (root: HTMLElement) =>
  within(root).queryAllByRole('button').filter((b) => b.hasAttribute('aria-expanded') && b.closest('li') !== null)
/** The row whose label is exactly `label` (longest catalog label wins). */
const rowFor = (root: HTMLElement, label: string): HTMLElement => {
  const labels = [...catalogFixture.fields.map((f) => f.label)].sort((a, b) => b.length - a.length)
  const hit = rowsIn(root).find((r) => labels.find((l) => (r.textContent ?? '').startsWith(l)) === label)
  if (!hit) throw new Error(`no row labelled ${label}`)
  return hit
}

async function open(path: string) {
  const app = renderApp(path)
  const pane = panes.fields()
  await waitFor(() => expect(rowsIn(pane).length).toBeGreaterThan(0))
  const field = () => new URLSearchParams(app.router.state.location.search).get('field')
  return { ...app, pane, field }
}

const scrollIntoView = vi.fn()
beforeEach(() => {
  scrollIntoView.mockClear()
  Element.prototype.scrollIntoView = scrollIntoView
})
afterEach(() => {
  vi.restoreAllMocks()
})

/** The elements scrollIntoView was called on with `block` (contexts of the spy). */
const scrolledWith = (block: string) =>
  scrollIntoView.mock.calls
    .map((args, i) => ({ el: scrollIntoView.mock.contexts[i] as HTMLElement, opts: args[0] as ScrollIntoViewOptions }))
    .filter((c) => c.opts?.block === block)
    .map((c) => c.el)

describe('field keyboard', () => {
  it('jAndKMoveSelectionInCatalogOrder', async () => {
    const order = displayOrder()
    const { user, field } = await open('/documents/D01')
    expect(field()).toBeNull()

    await user.keyboard('j')
    expect(field()).toBe(order[0])
    await user.keyboard('j')
    expect(field()).toBe(order[1])
    await user.keyboard('{ArrowDown}')
    expect(field()).toBe(order[2])
    await user.keyboard('k')
    expect(field()).toBe(order[1])
    await user.keyboard('{ArrowUp}')
    expect(field()).toBe(order[0])
    // The ends hold: no wrap-around.
    await user.keyboard('k')
    expect(field()).toBe(order[0])
  })

  it('kWithNothingSelectedPicksTheLastField', async () => {
    const order = displayOrder()
    const { user, field } = await open('/documents/D01')
    await user.keyboard('k')
    expect(field()).toBe(order[order.length - 1])
    await user.keyboard('j')
    expect(field()).toBe(order[order.length - 1])
  })

  it('movesOnlyThroughVisibleFieldsAfterFilters', async () => {
    // D01 with every third extracted field made weakest-level, so several risky rows sit apart.
    const doc = JSON.parse(JSON.stringify(D01)) as typeof D01
    const fields = doc.extraction.cases[0]?.fields ?? {}
    displayOrder()
      .filter((id) => fields[id]?.status === 'extracted')
      .forEach((id, i) => {
        const f = fields[id]
        if (f && i % 3 === 1) f.confidence = levels[levels.length - 1]
      })
    server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
    const risky = displayOrder().filter((id) => {
      const r = fields[id]
      return r !== undefined && isRisky(r, levels)
    })
    expect(risky.length).toBeGreaterThan(2)
    const { user, pane, field } = await open('/documents/D01')
    await user.click(within(pane).getByRole('button', { name: /risky only/i }))
    await waitFor(() => expect(rowsIn(pane)).toHaveLength(risky.length))

    for (const id of risky) {
      await user.keyboard('j')
      expect(field()).toBe(id)
    }
    await user.keyboard('k')
    expect(field()).toBe(risky[risky.length - 2])
  })

  it('escClearsSelection', async () => {
    const [first] = displayOrder()
    const { user, pane, field } = await open(`/documents/D01?field=${first}`)
    await waitFor(() => expect(rowFor(pane, labelOf(first as string))).toHaveAttribute('aria-expanded', 'true'))
    await user.keyboard('{Escape}')
    expect(field()).toBeNull()
    await waitFor(() => expect(rowsIn(pane).filter((r) => r.getAttribute('aria-expanded') === 'true')).toHaveLength(0))
  })

  it('enterTogglesTheInspector', async () => {
    const order = displayOrder()
    const { user, pane, field } = await open('/documents/D01')
    await user.keyboard('jj')
    expect(field()).toBe(order[1])
    const row = () => rowFor(pane, labelOf(order[1] as string))
    // Keyboard selection moves focus to the row, so Enter acts on it.
    await waitFor(() => expect(row()).toHaveFocus())
    await user.keyboard('{Enter}')
    expect(field()).toBeNull()
    await user.keyboard('{Enter}')
    expect(field()).toBe(order[1])

    // From the page (focus outside any control) Enter toggles the last selected field too.
    ;(document.activeElement as HTMLElement).blur()
    await user.keyboard('{Enter}')
    expect(field()).toBeNull()
    await user.keyboard('{Enter}')
    expect(field()).toBe(order[1])
  })

  it('escMovesFocusFromTheInspectorToItsRow', async () => {
    const id = displayOrder().find((x) => (d01Fields()[x]?.provenance?.length ?? 0) > 0) as string
    const { user, pane, field } = await open(`/documents/D01?field=${id}`)
    const row = rowFor(pane, labelOf(id))
    const inspector = document.getElementById(row.getAttribute('aria-controls') ?? '') as HTMLElement
    const [excerpt] = within(inspector).getAllByRole('button', { name: /show in text/i })
    ;(excerpt as HTMLElement).focus()
    await user.keyboard('{Escape}')
    expect(field()).toBeNull()
    await waitFor(() => expect(rowFor(pane, labelOf(id))).toHaveFocus())
  })

  it('shortcutsIgnoredInsideInputs', async () => {
    const text = catalogFixture.fields.find((f) => f.type === 'string' && d01Fields()[f.id]?.status === 'extracted')
    if (!text) throw new Error('D01 extracts no string field')
    const { user, pane, field } = await open(`/documents/D01?field=${text.id}`)
    const row = rowFor(pane, text.label)
    const inspector = document.getElementById(row.getAttribute('aria-controls') ?? '') as HTMLElement
    const input = within(inspector).getByLabelText(text.label)
    await user.click(input)
    await user.keyboard('jk{ArrowDown}{Escape}{Enter}')
    expect(field()).toBe(text.id)
    expect((input as HTMLInputElement).value).toMatch(/jk$/)
  })

  it('arrowsInTheDocumentPaneOrRailScrollInsteadOfMovingTheSelection', async () => {
    const order = displayOrder()
    const { field } = await open(`/documents/D01?field=${order[0]}`)
    const main = panes.document()
    main.focus()
    // fireEvent returns false when a listener called preventDefault (the pane would not scroll).
    expect(fireEvent.keyDown(main, { key: 'ArrowDown' })).toBe(true)
    expect(fireEvent.keyDown(main, { key: 'ArrowUp' })).toBe(true)
    expect(field()).toBe(order[0])

    const [link] = within(panes.rail()).getAllByRole('link')
    ;(link as HTMLElement).focus()
    expect(fireEvent.keyDown(link as HTMLElement, { key: 'ArrowDown' })).toBe(true)
    expect(field()).toBe(order[0])

    // j / k stay global: they still move the selection from the document pane.
    main.focus()
    fireEvent.keyDown(main, { key: 'j' })
    expect(field()).toBe(order[1])
  })

  it('shortcutsIgnoredWithModifierKeys', async () => {
    const order = displayOrder()
    const { user, field } = await open(`/documents/D01?field=${order[0]}`)
    await user.keyboard('{Control>}j{/Control}{Meta>}j{/Meta}{Alt>}j{/Alt}{Control>}{ArrowDown}{/Control}')
    expect(field()).toBe(order[0])
  })

  it('shortcutsIgnoredInsideAnOpenListbox', async () => {
    const coded = catalogFixture.fields.find(
      (f) => f.type === 'coded' && d01Fields()[f.id]?.status === 'extracted' && (f.options?.length ?? 0) > 1,
    )
    if (!coded) throw new Error('D01 extracts no coded field')
    const { user, pane, field } = await open(`/documents/D01?field=${coded.id}`)
    const row = rowFor(pane, coded.label)
    const inspector = document.getElementById(row.getAttribute('aria-controls') ?? '') as HTMLElement
    await user.click(within(inspector).getByRole('combobox', { name: coded.label }))
    const listbox = await screen.findByRole('listbox')
    await user.keyboard('jk')
    expect(field()).toBe(coded.id)
    // Esc closes the list first; the field stays selected.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(listbox).not.toBeInTheDocument()
    expect(field()).toBe(coded.id)
  })

  it('keyboardMoveScrollsRowIntoViewAndFocusesIt', async () => {
    const order = displayOrder()
    const { user, pane } = await open('/documents/D01')
    await user.keyboard('j')
    const row = rowFor(pane, labelOf(order[0] as string))
    await waitFor(() => expect(scrolledWith('nearest')).toContain(row))
    expect(row).toHaveFocus()
  })

  it('clickingQuoteScrollsItsRowIntoView', async () => {
    const { user, pane, field } = await open('/documents/D01')
    const text = await screen.findByRole('article', { name: 'Document text' })
    await waitFor(() => expect(text.querySelectorAll('.quote').length).toBeGreaterThan(0))
    await user.click(text.querySelector('.quote') as HTMLElement)
    const id = field() as string
    expect(id).not.toBeNull()
    const row = rowFor(pane, labelOf(id))
    await waitFor(() => expect(scrolledWith('nearest')).toContain(row))
    // A mouse user stays where they clicked: the row is revealed, not focused.
    expect(row).not.toHaveFocus()
  })

  it('rowClickDoesNotScrollTheRow', async () => {
    const order = displayOrder()
    const { user, pane, field } = await open('/documents/D01')
    await user.click(rowFor(pane, labelOf(order[1] as string)))
    expect(field()).toBe(order[1])
    expect(scrolledWith('nearest')).toHaveLength(0)
  })
})
