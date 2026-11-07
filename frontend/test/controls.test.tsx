import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CatalogField, DocumentDetail, FieldResult, FieldType } from '../src/api/types'
import { FieldControl } from '../src/components/controls/FieldControl'
import { D01, MP1, catalogFixture } from './fixtures'
import { server } from './msw/server'
import { panes, renderApp } from './renderApp'

const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const d01Fields = (): Record<string, FieldResult> => D01.extraction.cases[0]?.fields ?? {}

/** The first catalog field of `type` (in catalog order) that D01 extracts and that passes `pick`. Never by name. */
function fieldOfType(type: FieldType, pick: (f: CatalogField) => boolean = () => true): CatalogField {
  const hit = catalogFixture.fields.find((f) => f.type === type && d01Fields()[f.id]?.status === 'extracted' && pick(f))
  if (!hit) throw new Error(`D01 extracts no ${type} field matching the test's needs`)
  return hit
}
const modelValue = (f: CatalogField): unknown => d01Fields()[f.id]?.value

/** D01 with its single case changed by `mutate`, served for this test. */
function serveD01(mutate: (fields: Record<string, FieldResult>) => void): DocumentDetail {
  const doc = clone(D01)
  const kase = doc.extraction.cases[0]
  if (!kase) throw new Error('D01 has no case')
  mutate(kase.fields)
  server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
  return doc
}

const rowsIn = (root: HTMLElement) =>
  within(root).queryAllByRole('button').filter((b) => b.hasAttribute('aria-expanded') && b.closest('li') !== null)
/** The row whose label is exactly `label` (longest catalog label wins, so a short label never shadows a longer one). */
const rowFor = (root: HTMLElement, label: string): HTMLElement => {
  const labels = [...catalogFixture.fields.map((f) => f.label)].sort((a, b) => b.length - a.length)
  const hit = rowsIn(root).find((r) => labels.find((l) => (r.textContent ?? '').startsWith(l)) === label)
  if (!hit) throw new Error(`no row labelled ${label}`)
  return hit
}

/** Opens D01 (or `doc`) with `field` selected; returns the fields pane, the row and its inspector. */
async function openField(field: CatalogField, docId = 'D01', search = '') {
  const app = renderApp(`/documents/${docId}?field=${field.id}${search}`)
  const pane = panes.fields()
  await waitFor(() => expect(rowFor(pane, field.label)).toHaveAttribute('aria-expanded', 'true'))
  const row = () => rowFor(pane, field.label)
  const inspector = () => document.getElementById(row().getAttribute('aria-controls') ?? '') as HTMLElement
  return { ...app, pane, row, inspector }
}

const editedBadge = (row: HTMLElement) => within(row).queryByRole('img', { name: 'Edited' })
const optionLabel = (f: CatalogField, code: string) => f.options?.find((o) => o.code === code)?.label as string

afterEach(() => {
  vi.restoreAllMocks()
})

describe('catalog-driven controls', () => {
  it('dateFieldRendersDateInput', async () => {
    const field = fieldOfType('date')
    const { user, row, inspector } = await openField(field)
    const input = within(inspector()).getByLabelText(field.label)
    expect(input).toHaveAttribute('type', 'date')
    expect(input).toHaveValue(modelValue(field) as string)

    await user.clear(input)
    await user.type(input, '2026-03-05')
    await waitFor(() => expect(within(row()).getByText('5 Mar 2026')).toBeInTheDocument())
    expect(editedBadge(row())).not.toBeNull()
  })

  it('codedFieldRendersSelectWithCatalogOptionLabels', async () => {
    const field = fieldOfType('coded', (f) => (f.options?.length ?? 0) > 1)
    const options = field.options ?? []
    const current = modelValue(field) as string
    const { user, row, inspector } = await openField(field)
    const select = within(inspector()).getByRole('combobox', { name: field.label })
    expect(select).toHaveTextContent(optionLabel(field, current))

    await user.click(select)
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getAllByRole('option').map((o) => o.textContent)).toEqual(options.map((o) => o.label))

    const other = options.find((o) => o.code !== current) as { code: string; label: string }
    await user.click(within(listbox).getByRole('option', { name: other.label }))
    await waitFor(() => expect(within(row()).getByText(other.label)).toBeInTheDocument())
    expect(editedBadge(row())).not.toBeNull()
    expect(within(inspector()).getByRole('combobox', { name: field.label })).toHaveTextContent(other.label)
  })

  it('codedMultiRendersMultiSelect', async () => {
    const field = fieldOfType('coded_multi', (f) => (f.options?.length ?? 0) > 1)
    const options = field.options ?? []
    const current = modelValue(field) as string[]
    const { user, row, inspector } = await openField(field)
    // Selected options show as chips with their catalog labels, each with a remove button.
    const chips = within(inspector()).getByRole('list', { name: `${field.label}: selected` })
    expect(within(chips).getAllByRole('listitem').map((li) => li.textContent?.replace(/Remove.*$/, ''))).toEqual(
      current.map((c) => optionLabel(field, c)),
    )
    // The row shows the same values as chips.
    for (const c of current) expect(within(row()).getByText(optionLabel(field, c))).toBeInTheDocument()

    await user.click(within(inspector()).getByRole('button', { name: field.label }))
    const dialog = await screen.findByRole('dialog')
    const boxes = within(dialog).getAllByRole('checkbox')
    expect(boxes.map((b) => b.getAttribute('aria-label') ?? b.closest('label')?.textContent)).toEqual(
      options.map((o) => o.label),
    )
    for (const o of options) {
      const box = within(dialog).getByRole('checkbox', { name: o.label })
      if (current.includes(o.code)) expect(box).toBeChecked()
      else expect(box).not.toBeChecked()
    }
    const added = options.find((o) => !current.includes(o.code)) as { code: string; label: string }
    await user.click(within(dialog).getByRole('checkbox', { name: added.label }))
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    expect(within(inspector()).getByRole('list', { name: `${field.label}: selected` })).toHaveTextContent(added.label)
    expect(within(row()).getByText(added.label)).toBeInTheDocument()
    expect(editedBadge(row())).not.toBeNull()

    await user.click(within(inspector()).getByRole('button', { name: `Remove ${added.label}` }))
    await waitFor(() => expect(within(row()).queryByText(added.label)).toBeNull())
    // Back to the model's own selection: no longer a draft.
    expect(editedBadge(row())).toBeNull()
    expect(inspector()).toContainElement(document.activeElement as HTMLElement)
  })

  it('integerHonoursCatalogMinMax', async () => {
    const field = fieldOfType('integer', (f) => f.validation?.min !== undefined && f.validation.max !== undefined)
    const { min, max } = field.validation as { min: number; max: number }
    const { user, row, inspector } = await openField(field)
    const input = within(inspector()).getByLabelText(field.label)
    expect(input).toHaveAttribute('type', 'number')
    expect(input).toHaveAttribute('min', String(min))
    expect(input).toHaveAttribute('max', String(max))
    expect(input).toHaveAttribute('step', '1')

    await user.clear(input)
    await user.type(input, String(max + 1))
    expect(input).toHaveAttribute('aria-invalid', 'true')
    const message = document.getElementById(input.getAttribute('aria-describedby') ?? '')
    expect(message).toHaveTextContent(`at most ${max}`)
    // An out-of-range number is never committed: the row does not show it.
    expect(within(row()).queryByText(String(max + 1))).toBeNull()

    await user.clear(input)
    await user.type(input, '1.5')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(document.getElementById(input.getAttribute('aria-describedby') ?? '')).toHaveTextContent(/whole number/i)
    expect(within(row()).queryByText('1.5')).toBeNull()

    const valid = max - 1 === modelValue(field) ? max - 2 : max - 1
    await user.clear(input)
    await user.type(input, String(valid))
    expect(input).not.toHaveAttribute('aria-invalid', 'true')
    await waitFor(() => expect(editedBadge(row())).not.toBeNull())
    expect(within(row()).getByText(String(valid))).toBeInTheDocument()
  })

  it('numberFieldUsesStepAnyAndCatalogMin', async () => {
    const field = fieldOfType('number')
    const { inspector } = await openField(field)
    const input = within(inspector()).getByLabelText(field.label)
    expect(input).toHaveAttribute('step', 'any')
    if (field.validation?.min !== undefined) expect(input).toHaveAttribute('min', String(field.validation.min))
    else expect(input).not.toHaveAttribute('min')
    if (field.validation?.max !== undefined) expect(input).toHaveAttribute('max', String(field.validation.max))
    else expect(input).not.toHaveAttribute('max')
  })

  it('stringPatternShowsValidityMessage', async () => {
    const field = fieldOfType('string', (f) => f.validation?.pattern !== undefined)
    // Full match, like the backend's Pattern.matches().
    const pattern = new RegExp(`^(?:${field.validation?.pattern as string})$`, 'u')
    const { user, row, inspector } = await openField(field)
    const input = within(inspector()).getByLabelText(field.label)
    expect(input).toHaveValue(modelValue(field) as string)
    expect(input).not.toHaveAttribute('aria-invalid', 'true')

    const bad = 'x1 y'
    expect(pattern.test(bad)).toBe(false)
    await user.clear(input)
    await user.type(input, bad)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    const message = document.getElementById(input.getAttribute('aria-describedby') ?? '')
    expect(message).toHaveTextContent(field.validation?.pattern as string)
    // Text the pattern rejects is never committed: the row does not show it.
    expect(within(row()).queryByText(bad)).toBeNull()

    // A value the pattern accepts and that differs from the model: valid, and a draft.
    const good = ['ZZ', 'Q', 'AB', 'ZZZZ'].find((s) => pattern.test(s) && s !== modelValue(field))
    if (!good) throw new Error('no candidate matches the catalog pattern')
    await user.clear(input)
    await user.type(input, good)
    expect(input).not.toHaveAttribute('aria-invalid', 'true')
    expect(input.getAttribute('aria-describedby') ?? '').toBe('')
    await waitFor(() => expect(within(row()).getByText(good)).toBeInTheDocument())
    expect(editedBadge(row())).not.toBeNull()
  })

  it('stringListAddsAndRemovesItems', async () => {
    const field = fieldOfType('string_list', (f) => ((d01Fields()[f.id]?.value as string[] | undefined)?.length ?? 0) > 0)
    const current = modelValue(field) as string[]
    const first = current[0] as string
    const { user, row, inspector } = await openField(field)
    const items = () => within(within(inspector()).getByRole('list', { name: `${field.label}: items` })).queryAllByRole('listitem')
    expect(items().map((li) => li.textContent?.replace(/Remove.*$/, ''))).toEqual(current)
    for (const v of current) expect(within(row()).getByText(v)).toBeInTheDocument()

    const input = within(inspector()).getByLabelText(field.label)
    await user.type(input, '  zz-new-item  {Enter}')
    expect(items().map((li) => li.textContent?.replace(/Remove.*$/, ''))).toEqual([...current, 'zz-new-item'])
    expect(input).toHaveValue('')
    await waitFor(() => expect(within(row()).getByText('zz-new-item')).toBeInTheDocument())
    expect(editedBadge(row())).not.toBeNull()

    // A duplicate is refused with a message, and not added.
    await user.type(input, `${first}{Enter}`)
    expect(items()).toHaveLength(current.length + 1)
    expect(within(inspector()).getByText(/already/i)).toBeInTheDocument()
    await user.clear(input)

    // Whitespace alone adds nothing; the Add button adds like Enter.
    await user.type(input, '   ')
    await user.click(within(inspector()).getByRole('button', { name: 'Add' }))
    expect(items()).toHaveLength(current.length + 1)

    await user.click(within(inspector()).getByRole('button', { name: `Remove ${first}` }))
    expect(items().map((li) => li.textContent?.replace(/Remove.*$/, ''))).toEqual([...current.slice(1), 'zz-new-item'])
    await waitFor(() => expect(within(row()).queryByText(first)).toBeNull())
    expect(inspector()).toContainElement(document.activeElement as HTMLElement)
  })
})

describe('drafts', () => {
  it('editMarksRowEditedAndResetRestores', async () => {
    const field = fieldOfType('string', (f) => f.validation?.pattern === undefined)
    const model = modelValue(field) as string
    const level = d01Fields()[field.id]?.confidence as string
    const { user, pane, row, inspector } = await openField(field)
    expect(within(inspector()).queryByText('Edits are not saved yet')).toBeNull()
    expect(within(inspector()).queryByRole('button', { name: 'Reset to model value' })).toBeNull()
    expect(within(pane).queryByRole('button', { name: /^edited/ })).toBeNull()

    const input = within(inspector()).getByLabelText(field.label)
    await user.type(input, ' extra')
    const draft = `${model} extra`

    // The row shows the draft, the edited badge, and still the model's own state.
    await waitFor(() => expect(within(row()).getByText(draft)).toBeInTheDocument())
    expect(editedBadge(row())).not.toBeNull()
    expect(within(row()).getByText(`model: ${level}`)).toBeInTheDocument()
    expect(within(pane).getByRole('button', { name: 'edited: 1' })).toBeInTheDocument()
    expect(within(inspector()).getByText('Edits are not saved yet')).toBeInTheDocument()
    expect(input).toHaveFocus()

    // The draft survives collapsing and reopening the row (kept in memory).
    await user.click(row())
    await waitFor(() => expect(row()).toHaveAttribute('aria-expanded', 'false'))
    expect(within(row()).getByText(draft)).toBeInTheDocument()
    await user.click(row())
    await waitFor(() => expect(row()).toHaveAttribute('aria-expanded', 'true'))
    expect(within(inspector()).getByLabelText(field.label)).toHaveValue(draft)

    await user.click(within(inspector()).getByRole('button', { name: 'Reset to model value' }))
    await waitFor(() => expect(within(row()).getByText(model)).toBeInTheDocument())
    expect(editedBadge(row())).toBeNull()
    expect(within(row()).queryByText(`model: ${level}`)).toBeNull()
    expect(within(pane).queryByRole('button', { name: /^edited/ })).toBeNull()
    expect(within(inspector()).getByLabelText(field.label)).toHaveValue(model)
    expect(within(inspector()).queryByText('Edits are not saved yet')).toBeNull()
    expect(inspector()).toContainElement(document.activeElement as HTMLElement)
  })

  it('draftsAreKeptPerCase', async () => {
    const field = fieldOfType('string', (f) => f.validation?.pattern === undefined)
    const { user, pane, row, inspector } = await openField(field, 'MP1')
    expect(MP1.extraction.cases).toHaveLength(2)
    await user.type(within(inspector()).getByLabelText(field.label), ' case-one')
    await waitFor(() => expect(editedBadge(row())).not.toBeNull())

    await user.click(within(pane).getAllByRole('tab')[1] as HTMLElement)
    await waitFor(() => expect(within(pane).getAllByRole('tab')[1]).toHaveAttribute('aria-selected', 'true'))
    expect(editedBadge(row())).toBeNull()
    expect(within(pane).queryByRole('button', { name: /^edited/ })).toBeNull()

    await user.click(within(pane).getAllByRole('tab')[0] as HTMLElement)
    await waitFor(() => expect(editedBadge(row())).not.toBeNull())
    expect(within(pane).getByRole('button', { name: 'edited: 1' })).toBeInTheDocument()
  })

  it('conflictInspectorListsCandidates', async () => {
    const field = fieldOfType('date')
    serveD01((fields) => {
      fields[field.id] = { status: 'conflict', note: 'Candidates: 2026-03-12; 2026-03-14' }
    })
    const { user, row, inspector } = await openField(field)
    const items = within(within(inspector()).getByRole('list', { name: /candidates/i })).getAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual(['2026-03-12', '2026-03-14'])

    // The control starts empty: the model gave no value. Filling it is a draft.
    const input = within(inspector()).getByLabelText(field.label)
    expect(input).toHaveValue('')
    await user.type(input, '2026-03-12')
    await waitFor(() => expect(within(row()).getByText('12 Mar 2026')).toBeInTheDocument())
    expect(editedBadge(row())).not.toBeNull()
    expect(within(row()).getByText('model: conflict')).toBeInTheDocument()
  })
})

describe('FieldControl', () => {
  it('unsupportedFieldTypeShowsNotice', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const bogus = { ...fieldOfType('date'), type: 'colour' } as unknown as CatalogField
    render(<FieldControl id="x" field={bogus} value={null} onChange={() => {}} />)
    expect(screen.getByRole('note')).toHaveTextContent('Unsupported field type colour')
    expect(error).toHaveBeenCalledWith(expect.stringContaining('colour'))
  })

  it('patternMustMatchTheWholeValueLikeTheBackend', async () => {
    const field: CatalogField = { id: 'x', label: 'Two letters', section: 's', type: 'string', validation: { pattern: '[A-Z]{2}' } }
    const changes: unknown[] = []
    const user = userEvent.setup()
    render(<FieldControl id="c" field={field} value={null} onChange={(v) => changes.push(v)} />)
    const input = screen.getByLabelText('Two letters')
    await user.type(input, 'ABC')
    // An unanchored pattern still has to match all of the text: "ABC" contains "AB" but is not two letters.
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(changes).not.toContain('ABC')
    await user.clear(input)
    await user.type(input, 'AB')
    expect(input).not.toHaveAttribute('aria-invalid')
    expect(changes.at(-1)).toBe('AB')
  })

  it('everyCatalogTypeRendersALabelledControl', () => {
    for (const field of catalogFixture.fields) {
      const { unmount } = render(<FieldControl id="c" field={field} value={null} onChange={() => {}} />)
      // A programmatic label: the field's catalog label names the control.
      const named = screen.queryAllByLabelText(field.label)
      expect(named.length, `${field.type} control is labelled`).toBeGreaterThan(0)
      unmount()
    }
  })
})

describe('controls by keyboard alone', () => {
  const searchField = (router: { state: { location: { search: string } } }) =>
    new URLSearchParams(router.state.location.search).get('field')

  it('codedSelectWorksByKeyboard', async () => {
    const field = fieldOfType('coded', (f) => (f.options?.length ?? 0) > 1)
    const options = field.options ?? []
    const current = modelValue(field) as string
    const { user, router, row, inspector } = await openField(field)
    const trigger = within(inspector()).getByRole('combobox', { name: field.label })
    trigger.focus()

    // Enter opens the list; Esc closes it without a change, focus back on the trigger, field still selected.
    await user.keyboard('{Enter}')
    await screen.findByRole('listbox')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(trigger).toHaveFocus()
    expect(searchField(router)).toBe(field.id)
    expect(editedBadge(row())).toBeNull()

    // Open again, move to another option with the arrows, choose it with Enter.
    await user.keyboard('{Enter}')
    const listbox = await screen.findByRole('listbox')
    const at = options.findIndex((o) => o.code === current)
    const target = at === options.length - 1 ? options[at - 1] : options[at + 1]
    await waitFor(() => expect(within(listbox).getByRole('option', { name: optionLabel(field, current) })).toHaveFocus())
    await user.keyboard(at === options.length - 1 ? '{ArrowUp}' : '{ArrowDown}')
    await waitFor(() => expect(within(listbox).getByRole('option', { name: target?.label })).toHaveFocus())
    await user.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(within(inspector()).getByRole('combobox', { name: field.label })).toHaveTextContent(target?.label as string)
    await waitFor(() => expect(within(inspector()).getByRole('combobox', { name: field.label })).toHaveFocus())
    expect(editedBadge(row())).not.toBeNull()
    expect(searchField(router)).toBe(field.id)
  })

  it('codedMultiSelectWorksByKeyboard', async () => {
    const field = fieldOfType('coded_multi', (f) => (f.options?.length ?? 0) > 1)
    const options = field.options ?? []
    const current = modelValue(field) as string[]
    const { user, router, row, inspector } = await openField(field)
    const trigger = within(inspector()).getByRole('button', { name: field.label })
    trigger.focus()

    // Enter opens the list and focus goes to the first checkbox; Space toggles it.
    await user.keyboard('{Enter}')
    const dialog = await screen.findByRole('dialog')
    const first = options[0] as { code: string; label: string }
    const box = within(dialog).getByRole('checkbox', { name: first.label })
    await waitFor(() => expect(box).toHaveFocus())
    const wasChecked = current.includes(first.code)
    await user.keyboard(' ')
    if (wasChecked) expect(box).not.toBeChecked()
    else expect(box).toBeChecked()
    await waitFor(() => expect(editedBadge(row())).not.toBeNull())

    // Esc closes the list and returns focus to the trigger; the field stays selected.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(trigger).toHaveFocus()
    expect(searchField(router)).toBe(field.id)

    // Space opens it too; toggling the same option back undoes the draft.
    await user.keyboard(' ')
    const again = await screen.findByRole('dialog')
    const same = within(again).getByRole('checkbox', { name: first.label })
    await waitFor(() => expect(same).toHaveFocus())
    await user.keyboard(' ')
    await waitFor(() => expect(editedBadge(row())).toBeNull())
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(trigger).toHaveFocus()
  })
})

describe('inspector state', () => {
  it('validityMessageIsAnnouncedAsAnAlert', async () => {
    const field = fieldOfType('integer', (f) => f.validation?.max !== undefined)
    const { user, inspector } = await openField(field)
    const input = within(inspector()).getByLabelText(field.label)
    await user.clear(input)
    await user.type(input, String((field.validation?.max as number) + 1))
    const alert = within(inspector()).getByRole('alert')
    expect(alert).toHaveTextContent(`at most ${field.validation?.max as number}`)
    expect(input.getAttribute('aria-describedby')).toBe(alert.id)
  })

  it('controlStateResetsWhenTheCaseChanges', async () => {
    const field = catalogFixture.fields.find((f) => f.type === 'string_list')
    if (!field) throw new Error('the catalog has no string_list field')
    const { user, pane, inspector } = await openField(field, 'MP1')
    // A half-typed item lives only in the control, not in the draft.
    await user.type(within(inspector()).getByLabelText(field.label), 'half-typed')
    expect(within(inspector()).getByLabelText(field.label)).toHaveValue('half-typed')

    await user.click(within(pane).getAllByRole('tab')[1] as HTMLElement)
    await waitFor(() => expect(within(pane).getAllByRole('tab')[1]).toHaveAttribute('aria-selected', 'true'))
    await waitFor(() => expect(within(inspector()).getByLabelText(field.label)).toHaveValue(''))
  })
})
