import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogField, FieldResult, FieldType } from '../src/api/types'
import { isRisky } from '../src/lib/risk'
import { D01, catalogFixture } from './fixtures'
import { server } from './msw/server'
import { panes, renderApp } from './renderApp'

const levels = catalogFixture.confidence_levels
const total = catalogFixture.fields.length
const d01Fields = (): Record<string, FieldResult> => D01.extraction.cases[0]?.fields ?? {}
/** Catalog field ids in display order: catalog sections, then catalog field order inside each. Never by name. */
const displayOrder = () =>
  catalogFixture.sections.flatMap((s) => catalogFixture.fields.filter((f) => f.section === s.id)).map((f) => f.id)
const fieldOf = (id: string): CatalogField => catalogFixture.fields.find((f) => f.id === id) as CatalogField
const labelOf = (id: string): string => fieldOf(id).label

/** The first field of `type` in display order that D01 extracts and that passes `pick`. Never by name. */
function fieldOfType(type: FieldType, pick: (f: CatalogField) => boolean = () => true): CatalogField {
  const id = displayOrder().find((x) => fieldOf(x).type === type && d01Fields()[x]?.status === 'extracted' && pick(fieldOf(x)))
  if (!id) throw new Error(`D01 extracts no ${type} field matching the test's needs`)
  return fieldOf(id)
}
/** A free-text field (no catalog pattern), so any typed text is a valid edit. */
const freeText = () => fieldOfType('string', (f) => f.validation?.pattern === undefined)

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
  const row = (id: string) => rowFor(pane, labelOf(id))
  const inspector = (id: string) => document.getElementById(row(id).getAttribute('aria-controls') ?? '') as HTMLElement
  return { ...app, pane, field, row, inspector }
}

/** Opens D01 with `id` selected, waits for its inspector and puts focus on its row. */
async function openOnRow(id: string) {
  const app = await open(`/documents/D01?field=${id}`)
  await waitFor(() => expect(app.row(id)).toHaveAttribute('aria-expanded', 'true'))
  app.row(id).focus()
  return app
}

/** PATCH requests seen by the mock server (path and JSON body). */
function capturePatches() {
  const seen: { url: string; body: unknown }[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PATCH') seen.push({ url: new URL(request.url).pathname, body: await request.clone().json() })
  })
  return seen
}
const fieldUrl = (id: string) => `/api/documents/D01/cases/0/fields/${id}`
const badge = (row: HTMLElement, name: string) => within(row).queryByRole('img', { name })
const riskyToggle = (pane: HTMLElement) => within(pane).getByRole('button', { name: /risky only/i })

const scrollIntoView = vi.fn()
beforeEach(() => {
  scrollIntoView.mockClear()
  Element.prototype.scrollIntoView = scrollIntoView
})
afterEach(() => {
  server.events.removeAllListeners()
  vi.restoreAllMocks()
})

/** The elements scrollIntoView was called on with `block` (contexts of the spy). */
const scrolledWith = (block: string) =>
  scrollIntoView.mock.calls
    .map((args, i) => ({ el: scrollIntoView.mock.contexts[i] as HTMLElement, opts: args[0] as ScrollIntoViewOptions }))
    .filter((c) => c.opts?.block === block)
    .map((c) => c.el)

describe('moving between fields', () => {
  it('arrowsNavigateOnlyInsideFieldsPane', async () => {
    const order = displayOrder()
    const { user, pane, field, row } = await open('/documents/D01')
    expect(field()).toBeNull()
    // Focus in the fields pane, nothing selected: ↑ picks the last field, ↓ the first.
    riskyToggle(pane).focus()
    await user.keyboard('{ArrowUp}')
    expect(field()).toBe(order[order.length - 1])
    await user.keyboard('{Escape}')
    riskyToggle(pane).focus()
    await user.keyboard('{ArrowDown}')
    expect(field()).toBe(order[0])
    // The selected row takes focus, so the arrows keep working from it.
    await waitFor(() => expect(row(order[0] as string)).toHaveFocus())
    await user.keyboard('{ArrowDown}')
    expect(field()).toBe(order[1])
    await user.keyboard('{ArrowDown}')
    expect(field()).toBe(order[2])
    await user.keyboard('{ArrowUp}')
    expect(field()).toBe(order[1])
    await user.keyboard('{ArrowUp}')
    expect(field()).toBe(order[0])
    // The ends hold: no wrap-around.
    await user.keyboard('{ArrowUp}')
    expect(field()).toBe(order[0])
    // Moving takes the key: the pane does not scroll as well.
    expect(fireEvent.keyDown(row(order[0] as string), { key: 'ArrowDown' })).toBe(false)
    expect(field()).toBe(order[1])
  })

  it('arrowsOutsideFieldsPaneDoNotNavigate', async () => {
    const order = displayOrder()
    const { field } = await open(`/documents/D01?field=${order[0]}`)
    // fireEvent returns false when a listener called preventDefault (the pane would not scroll).
    const main = panes.document()
    main.focus()
    expect(fireEvent.keyDown(main, { key: 'ArrowDown' })).toBe(true)
    expect(fireEvent.keyDown(main, { key: 'ArrowUp' })).toBe(true)
    expect(field()).toBe(order[0])

    const [link] = within(panes.rail()).getAllByRole('link')
    ;(link as HTMLElement).focus()
    expect(fireEvent.keyDown(link as HTMLElement, { key: 'ArrowDown' })).toBe(true)
    expect(field()).toBe(order[0])

    // Nothing focused: the page scrolls as usual.
    ;(document.activeElement as HTMLElement).blur()
    expect(fireEvent.keyDown(document.body, { key: 'ArrowDown' })).toBe(true)
    expect(field()).toBe(order[0])
  })

  it('jAndKNoLongerNavigate', async () => {
    const order = displayOrder()
    const { user, pane, field } = await open(`/documents/D01?field=${order[1]}`)
    // From the document pane: nothing happens.
    panes.document().focus()
    await user.keyboard('jk')
    expect(field()).toBe(order[1])
    // With nothing selected, from the fields pane or the document: nothing happens either.
    await user.keyboard('{Escape}')
    expect(field()).toBeNull()
    riskyToggle(pane).focus()
    await user.keyboard('jk')
    expect(field()).toBeNull()
    panes.document().focus()
    await user.keyboard('jk')
    expect(field()).toBeNull()
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
    // The toggle keeps focus, inside the fields pane.
    await user.click(riskyToggle(pane))
    await waitFor(() => expect(rowsIn(pane)).toHaveLength(risky.length))

    for (const id of risky) {
      await user.keyboard('{ArrowDown}')
      expect(field()).toBe(id)
    }
    await user.keyboard('{ArrowUp}')
    expect(field()).toBe(risky[risky.length - 2])
  })

  it('keyboardMoveScrollsRowIntoViewAndFocusesIt', async () => {
    const order = displayOrder()
    const { user, pane, row } = await open('/documents/D01')
    riskyToggle(pane).focus()
    await user.keyboard('{ArrowDown}')
    const first = row(order[0] as string)
    await waitFor(() => expect(scrolledWith('nearest')).toContain(first))
    expect(first).toHaveFocus()
  })

  it('clickingQuoteScrollsItsRowIntoView', async () => {
    const { user, field, row } = await open('/documents/D01')
    const text = await screen.findByRole('article', { name: 'Document text' })
    await waitFor(() => expect(text.querySelectorAll('.quote').length).toBeGreaterThan(0))
    await user.click(text.querySelector('.quote') as HTMLElement)
    const id = field() as string
    expect(id).not.toBeNull()
    await waitFor(() => expect(scrolledWith('nearest')).toContain(row(id)))
    // A mouse user stays where they clicked: the row is revealed, not focused.
    expect(row(id)).not.toHaveFocus()
  })

  it('rowClickDoesNotScrollTheRow', async () => {
    const order = displayOrder()
    const { user, field, row } = await open('/documents/D01')
    await user.click(row(order[1] as string))
    expect(field()).toBe(order[1])
    expect(scrolledWith('nearest')).toHaveLength(0)
  })
})

describe('editing from the keyboard', () => {
  it('enterStartsEditAndEnterAgainSaves', async () => {
    const order = displayOrder()
    const text = freeText()
    const next = order[order.indexOf(text.id) + 1] as string
    const model = d01Fields()[text.id]?.value as string
    const seen = capturePatches()
    const { user, field, row, inspector } = await openOnRow(text.id)

    await user.keyboard('{Enter}')
    const input = within(inspector(text.id)).getByLabelText(text.label) as HTMLInputElement
    expect(input).toHaveFocus()
    expect(input).toHaveValue(model)
    expect(input.selectionStart).toBe(model.length)
    // Enter on the selected row starts editing; it does not collapse the row.
    expect(field()).toBe(text.id)
    expect(seen).toEqual([])

    await user.keyboard(' extra{Enter}')
    await waitFor(() => expect(badge(row(text.id), 'Manual edit')).not.toBeNull())
    expect(seen).toEqual([{ url: fieldUrl(text.id), body: { action: 'edit', value: `${model} extra` } }])
    await waitFor(() => expect(field()).toBe(next))
    await waitFor(() => expect(row(next)).toHaveFocus())
  })

  it('enterInUnchangedEditorSendsNothingAndKeepsSelection', async () => {
    const text = freeText()
    const seen = capturePatches()
    const { user, field, row, inspector } = await openOnRow(text.id)
    await user.keyboard('{Enter}')
    expect(within(inspector(text.id)).getByLabelText(text.label)).toHaveFocus()

    // Nothing changed: Enter closes the editor and hands focus back to the row.
    await user.keyboard('{Enter}')
    expect(row(text.id)).toHaveFocus()
    expect(field()).toBe(text.id)
    expect(row(text.id)).toHaveAttribute('aria-expanded', 'true')
    expect(seen).toEqual([])
  })

  it('typingOnTextFieldSeedsEditorWithCharacter', async () => {
    const text = freeText()
    const seen = capturePatches()
    const { user, row, inspector } = await openOnRow(text.id)

    await user.keyboard('Z')
    const input = within(inspector(text.id)).getByLabelText(text.label) as HTMLInputElement
    // The first character replaces the value; the caret sits after it.
    expect(input).toHaveFocus()
    expect(input).toHaveValue('Z')
    expect(input.selectionStart).toBe(1)
    await user.keyboard('oe{Enter}')
    await waitFor(() => expect(badge(row(text.id), 'Manual edit')).not.toBeNull())
    expect(seen).toEqual([{ url: fieldUrl(text.id), body: { action: 'edit', value: 'Zoe' } }])
  })

  it('typingOnNumberFieldSeedsEditorWithDigit', async () => {
    const num = fieldOfType('integer', (f) => (f.validation?.min ?? 0) <= 7 && (f.validation?.max ?? 99) >= 7)
    const { user, inspector } = await openOnRow(num.id)
    await user.keyboard('7')
    const input = within(inspector(num.id)).getByLabelText(num.label)
    expect(input).toHaveFocus()
    expect(input).toHaveValue(7)
  })

  it('typingOnCodedFieldOpensSelectWithTypeahead', async () => {
    const coded = fieldOfType('coded', (f) => (f.options?.length ?? 0) > 1)
    const options = coded.options ?? []
    const current = d01Fields()[coded.id]?.value as string
    const firstStarting = (letter: string) => options.find((o) => o.label.toLowerCase().startsWith(letter))
    // An option other than the current one that is the first to start with its letter.
    const target = options.find((o) => o.code !== current && /^[a-z]/i.test(o.label) && firstStarting(o.label[0]?.toLowerCase() ?? '') === o)
    if (!target) throw new Error('no option is reachable by its first letter')
    const letter = (target.label[0] as string).toLowerCase()
    const seen = capturePatches()
    const { user, row, inspector } = await openOnRow(coded.id)
    // Looked up first: the open list hides the rest of the page from the accessibility tree.
    const trigger = within(inspector(coded.id)).getByRole('combobox', { name: coded.label })

    await user.keyboard(letter)
    const listbox = await screen.findByRole('listbox')
    await waitFor(() => expect(within(listbox).getByRole('option', { name: target.label })).toHaveFocus())
    // Highlighted, not chosen: nothing changes until Enter.
    expect(trigger).toHaveTextContent(options.find((o) => o.code === current)?.label as string)

    await user.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    await waitFor(() => expect(trigger).toHaveFocus())
    expect(trigger).toHaveTextContent(target.label)
    expect(seen).toEqual([])
    // Enter again saves the choice.
    await user.keyboard('{Enter}')
    await waitFor(() => expect(badge(row(coded.id), 'Manual edit')).not.toBeNull())
    expect(seen).toEqual([{ url: fieldUrl(coded.id), body: { action: 'edit', value: target.code } }])
  })

  it('typingADigitOnDateFieldGoesOnToTheDateInput', async () => {
    const date = fieldOfType('date')
    const { row, inspector } = await openOnRow(date.id)
    const input = within(inspector(date.id)).getByLabelText(date.label)
    // A digit is not taken: the date input has focus first, and the browser types the digit into it.
    expect(fireEvent.keyDown(row(date.id), { key: '1' })).toBe(true)
    expect(input).toHaveFocus()
    // Anything else just opens the input.
    row(date.id).focus()
    expect(fireEvent.keyDown(row(date.id), { key: 'x' })).toBe(false)
    expect(input).toHaveFocus()
  })

  it('typingOnStringListFieldGoesIntoTheAddInput', async () => {
    const list = fieldOfType('string_list')
    const { user, inspector } = await openOnRow(list.id)
    await user.keyboard('q')
    const input = within(inspector(list.id)).getByLabelText(list.label)
    expect(input).toHaveFocus()
    expect(input).toHaveValue('q')
  })

  it('typingOnCodedMultiFieldFiltersItsOptions', async () => {
    const multi = fieldOfType('coded_multi', (f) => (f.options?.length ?? 0) > 1)
    const first = (multi.options ?? [])[0] as { code: string; label: string }
    const letter = first.label[0] as string
    const { user } = await openOnRow(multi.id)
    await user.keyboard(letter)
    const dialog = await screen.findByRole('dialog')
    const search = within(dialog).getByRole('searchbox', { name: 'Filter options' })
    await waitFor(() => expect(search).toHaveFocus())
    expect(search).toHaveValue(letter)
    const shown = within(dialog).getAllByRole('checkbox').map((b) => b.closest('label')?.textContent ?? '')
    expect(shown).toContain(first.label)
    for (const label of shown) expect(label.toLowerCase()).toContain(letter.toLowerCase())
  })

  it('escCancelsEditWithoutReviewing', async () => {
    const text = freeText()
    const model = d01Fields()[text.id]?.value as string
    const seen = capturePatches()
    const { user, pane, field, row, inspector } = await openOnRow(text.id)
    await user.keyboard('Qwerty')
    const input = () => within(inspector(text.id)).getByLabelText(text.label)
    expect(input()).toHaveValue('Qwerty')
    expect(within(inspector(text.id)).getByRole('button', { name: 'Save' })).toBeEnabled()

    await user.keyboard('{Escape}')
    // The shown value comes back, focus returns to the row, the field stays selected and unreviewed.
    expect(input()).toHaveValue(model)
    expect(row(text.id)).toHaveFocus()
    expect(field()).toBe(text.id)
    expect(within(inspector(text.id)).getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(badge(row(text.id), 'Manual edit')).toBeNull()
    expect(within(pane).getByText(`0 / ${total} reviewed`)).toBeInTheDocument()
    expect(seen).toEqual([])
  })

  it('escWithoutEditClearsSelection', async () => {
    const [first] = displayOrder() as [string]
    const { user, pane, field, row } = await openOnRow(first)
    await user.keyboard('{Escape}')
    expect(field()).toBeNull()
    await waitFor(() => expect(rowsIn(pane).filter((r) => r.getAttribute('aria-expanded') === 'true')).toHaveLength(0))
    expect(row(first)).toHaveFocus()
  })

  it('escMovesFocusFromTheInspectorToItsRow', async () => {
    const id = displayOrder().find((x) => (d01Fields()[x]?.provenance?.length ?? 0) > 0) as string
    const { user, field, row, inspector } = await open(`/documents/D01?field=${id}`)
    const [excerpt] = within(inspector(id)).getAllByRole('button', { name: /show in text/i })
    ;(excerpt as HTMLElement).focus()
    await user.keyboard('{Escape}')
    expect(field()).toBeNull()
    await waitFor(() => expect(row(id)).toHaveFocus())
  })

  it('saveAdvancesToNextUnreviewed', async () => {
    const order = displayOrder()
    const text = freeText()
    const at = order.indexOf(text.id)
    const [next, after] = [order[at + 1], order[at + 2]] as [string, string]
    const { user, field, row } = await openOnRow(next)
    // Review the next field first, so the save has to skip it.
    await user.keyboard('{Alt>}a{/Alt}')
    await waitFor(() => expect(badge(row(next), 'Accepted')).not.toBeNull())

    await user.click(row(text.id))
    expect(field()).toBe(text.id)
    await user.keyboard('{Enter} more{Enter}')
    await waitFor(() => expect(badge(row(text.id), 'Manual edit')).not.toBeNull())
    await waitFor(() => expect(field()).toBe(after))
    await waitFor(() => expect(row(after)).toHaveFocus())
  })
})

describe('review shortcuts', () => {
  it('altAAcceptsAltRRejectsThisField', async () => {
    const [first, second, third] = displayOrder() as [string, string, string]
    const seen = capturePatches()
    const { user, field, row } = await openOnRow(first)

    await user.keyboard('{Alt>}a{/Alt}')
    await waitFor(() => expect(badge(row(first), 'Accepted')).not.toBeNull())
    await waitFor(() => expect(field()).toBe(second))
    await waitFor(() => expect(row(second)).toHaveFocus())

    await user.keyboard('{Alt>}r{/Alt}')
    await waitFor(() => expect(badge(row(second), 'Rejected')).not.toBeNull())
    await waitFor(() => expect(field()).toBe(third))
    expect(seen).toEqual([
      { url: fieldUrl(first), body: { action: 'accept' } },
      { url: fieldUrl(second), body: { action: 'reject' } },
    ])
  })

  it('altShiftRRejectAllAsksConfirmation', async () => {
    const [first] = displayOrder() as [string]
    const seen = capturePatches()
    const { user, pane, row } = await openOnRow(first)

    await user.keyboard('{Alt>}{Shift>}r{/Shift}{/Alt}')
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent(`${total} fields`)
    expect(seen).toEqual([])
    // Esc cancels: nothing is sent, focus goes back where it was.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(seen).toEqual([])
    expect(row(first)).toHaveFocus()

    await user.keyboard('{Alt>}{Shift>}r{/Shift}{/Alt}')
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: `Reject ${total} fields` }))
    await waitFor(() => expect(within(pane).getByText(`${total} / ${total} reviewed`)).toBeInTheDocument())
    expect(seen).toEqual([{ url: '/api/documents/D01/cases/0/fields', body: { action: 'reject' } }])
  })

  it('altShiftAAcceptsAllWithoutAsking', async () => {
    const [first] = displayOrder() as [string]
    const seen = capturePatches()
    const { user, pane } = await openOnRow(first)
    await user.keyboard('{Alt>}{Shift>}a{/Shift}{/Alt}')
    await waitFor(() => expect(within(pane).getByText(`${total} / ${total} reviewed`)).toBeInTheDocument())
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(seen).toEqual([{ url: '/api/documents/D01/cases/0/fields', body: { action: 'accept' } }])
  })
})

describe('keys that belong elsewhere', () => {
  it('shortcutsIgnoredInsideInputs', async () => {
    const text = freeText()
    const seen = capturePatches()
    const { user, field, inspector } = await open(`/documents/D01?field=${text.id}`)
    const input = within(inspector(text.id)).getByLabelText(text.label)
    await user.click(input)
    await user.keyboard('jk{ArrowDown}{ArrowUp}{Alt>}a{/Alt}')
    expect(field()).toBe(text.id)
    expect((input as HTMLInputElement).value).toMatch(/jk/)
    expect(seen).toEqual([])
  })

  it('shortcutsIgnoredWithModifierKeys', async () => {
    const order = displayOrder()
    const seen = capturePatches()
    const { user, field, inspector } = await openOnRow(order[0] as string)
    await user.keyboard('{Control>}a{/Control}{Meta>}a{/Meta}{Control>}{ArrowDown}{/Control}{Control>}{Alt>}a{/Alt}{/Control}')
    expect(field()).toBe(order[0])
    // No edit started, no review sent.
    expect(inspector(order[0] as string)).not.toContainElement(document.activeElement as HTMLElement)
    expect(seen).toEqual([])
  })

  it('shortcutsIgnoredInsideAnOpenListbox', async () => {
    const coded = fieldOfType('coded', (f) => (f.options?.length ?? 0) > 1)
    const seen = capturePatches()
    const { user, field, inspector } = await open(`/documents/D01?field=${coded.id}`)
    await user.click(within(inspector(coded.id)).getByRole('combobox', { name: coded.label }))
    const listbox = await screen.findByRole('listbox')
    await user.keyboard('{ArrowDown}{Alt>}a{/Alt}')
    expect(field()).toBe(coded.id)
    // Esc closes the list first; the field stays selected.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(listbox).not.toBeInTheDocument()
    expect(field()).toBe(coded.id)
    expect(seen).toEqual([])
  })
})
