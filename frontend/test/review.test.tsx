import { screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CatalogField, FieldResult } from '../src/api/types'
import { flagInfo } from '../src/lib/flags'
import { D01, catalogFixture } from './fixtures'
import { problem } from './msw/handlers'
import { server } from './msw/server'
import { panes, renderApp } from './renderApp'

const d01Fields = (): Record<string, FieldResult> => D01.extraction.cases[0]?.fields ?? {}
/** Catalog field ids in display order: catalog sections, then catalog field order inside each. Never by name. */
const displayOrder = () =>
  catalogFixture.sections.flatMap((s) => catalogFixture.fields.filter((f) => f.section === s.id)).map((f) => f.id)
const fieldOf = (id: string): CatalogField => catalogFixture.fields.find((f) => f.id === id) as CatalogField
const total = catalogFixture.fields.length

const rowsIn = (root: HTMLElement) =>
  within(root).queryAllByRole('button').filter((b) => b.hasAttribute('aria-expanded') && b.closest('li') !== null)
/** The row whose label is exactly `label` (longest catalog label wins, so a short label never shadows a longer one). */
const rowFor = (root: HTMLElement, label: string): HTMLElement => {
  const labels = [...catalogFixture.fields.map((f) => f.label)].sort((a, b) => b.length - a.length)
  const hit = rowsIn(root).find((r) => labels.find((l) => (r.textContent ?? '').startsWith(l)) === label)
  if (!hit) throw new Error(`no row labelled ${label}`)
  return hit
}

/** PATCH requests seen by the mock server (path and JSON body). */
function capturePatches() {
  const seen: { url: string; body: unknown }[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PATCH') seen.push({ url: new URL(request.url).pathname, body: await request.clone().json() })
  })
  return seen
}

afterEach(() => {
  server.events.removeAllListeners()
  vi.restoreAllMocks()
})

/** Opens `docId` with `fieldId` selected and waits for its inspector. */
async function openField(fieldId: string, docId = 'D01') {
  const app = renderApp(`/documents/${docId}?field=${fieldId}`)
  const pane = panes.fields()
  const label = fieldOf(fieldId).label
  await waitFor(() => expect(rowFor(pane, label)).toHaveAttribute('aria-expanded', 'true'))
  const row = (id = fieldId) => rowFor(pane, fieldOf(id).label)
  const inspector = () => document.getElementById(row().getAttribute('aria-controls') ?? '') as HTMLElement
  const selected = () => new URLSearchParams(app.router.state.location.search).get('field')
  return { ...app, pane, row, inspector, selected }
}

const badge = (row: HTMLElement, name: string) => within(row).queryByRole('img', { name })
const struck = (row: HTMLElement) => [...row.querySelectorAll('s')].map((s) => s.textContent)
const bar = (root: HTMLElement) => within(root).getByRole('progressbar')

describe('review buttons', () => {
  it('acceptThisMarksRowAcceptedAndAdvances', async () => {
    const [first, second] = displayOrder() as [string, string]
    const level = d01Fields()[first]?.confidence as string
    const { user, row, inspector, selected } = await openField(first)
    // Both places carry the buttons; the shortcut is in the tooltip and aria-keyshortcuts.
    const toolbar = screen.getByRole('group', { name: `Review ${fieldOf(first).label}` })
    expect(within(toolbar).getByRole('button', { name: 'Accept this' })).toHaveAttribute('aria-keyshortcuts', 'Alt+A')
    expect(within(inspector()).getByRole('button', { name: 'Reject this' })).toHaveAttribute('aria-keyshortcuts', 'Alt+R')
    expect(within(inspector()).queryByRole('button', { name: 'Reset' })).toBeNull()

    await user.click(within(inspector()).getByRole('button', { name: 'Accept this' }))
    await waitFor(() => expect(selected()).toBe(second))
    await waitFor(() => expect(badge(row(first), 'Accepted')).not.toBeNull())
    expect(badge(row(first), 'Accepted')).toHaveTextContent('✓')
    // The model's confidence stays visible beside the review.
    expect(badge(row(first), `Confidence: ${level}`)).not.toBeNull()
    // The next unreviewed field opens, with focus on its row.
    await waitFor(() => expect(row(second)).toHaveAttribute('aria-expanded', 'true'))
    expect(row(second)).toHaveFocus()
  })

  it('rejectThisStrikesValueAndShowsRejectedBadge', async () => {
    const id = displayOrder().find((x) => fieldOf(x).type === 'string') as string
    const model = d01Fields()[id]?.value as string
    const { user, row } = await openField(id)
    const toolbar = screen.getByRole('group', { name: `Review ${fieldOf(id).label}` })
    await user.click(within(toolbar).getByRole('button', { name: 'Reject this' }))
    await waitFor(() => expect(badge(row(), 'Rejected')).not.toBeNull())
    expect(badge(row(), 'Rejected')).toHaveTextContent('⊘')
    expect(badge(row(), 'Rejected')).toHaveTextContent('Rejected')
    expect(struck(row())).toEqual([model])
  })

  it('saveEditShowsManualEditBadgeWithModelValueStruck', async () => {
    const order = displayOrder()
    const id = order.find((x) => fieldOf(x).type === 'string' && fieldOf(x).validation?.pattern === undefined) as string
    const next = order[order.indexOf(id) + 1] as string
    const model = d01Fields()[id]?.value as string
    const seen = capturePatches()
    const { user, row, inspector, selected } = await openField(id)
    const input = within(inspector()).getByLabelText(fieldOf(id).label)
    await user.type(input, ' extra')
    // Nothing is shown as edited before it is saved.
    expect(badge(row(), 'Manual edit')).toBeNull()
    // Enter inside the control saves it.
    await user.keyboard('{Enter}')

    await waitFor(() => expect(badge(row(), 'Manual edit')).not.toBeNull())
    expect(seen).toEqual([{ url: `/api/documents/D01/cases/0/fields/${id}`, body: { action: 'edit', value: `${model} extra` } }])
    expect(badge(row(), 'Manual edit')).toHaveTextContent('✎')
    expect(within(row()).getByText(`${model} extra`)).toBeInTheDocument()
    expect(struck(row())).toEqual([model])
    await waitFor(() => expect(selected()).toBe(next))
  })

  it('serverValidationErrorKeepsEditorOpen', async () => {
    const id = displayOrder().find((x) => fieldOf(x).type === 'string' && fieldOf(x).validation?.pattern === undefined) as string
    server.use(http.patch('*/api/documents/:id/cases/:caseIndex/fields/:fieldId', () =>
      problem(400, 'Invalid value for this field: rejected by the server')))
    const { user, row, inspector, selected } = await openField(id)
    const input = within(inspector()).getByLabelText(fieldOf(id).label)
    await user.type(input, ' bad')
    await user.click(within(inspector()).getByRole('button', { name: 'Save' }))

    const alert = await within(inspector()).findByRole('alert')
    expect(alert).toHaveTextContent('rejected by the server')
    expect(selected()).toBe(id)
    expect(row()).toHaveAttribute('aria-expanded', 'true')
    expect(within(inspector()).getByLabelText(fieldOf(id).label)).toHaveValue(`${String(d01Fields()[id]?.value)} bad`)
    expect(badge(row(), 'Manual edit')).toBeNull()
  })

  it('resetReturnsToUnreviewed', async () => {
    const [first] = displayOrder() as [string]
    const { user, pane, row, selected } = await openField(first)
    const toolbar = () => screen.getByRole('group', { name: `Review ${fieldOf(first).label}` })
    expect(within(toolbar()).queryByRole('button', { name: 'Reset' })).toBeNull()
    await user.click(within(toolbar()).getByRole('button', { name: 'Accept this' }))
    await waitFor(() => expect(badge(row(), 'Accepted')).not.toBeNull())
    // Accepting moved on to the next field; come back to this one.
    await waitFor(() => expect(row()).toHaveAttribute('aria-expanded', 'false'))
    await user.click(row())
    await waitFor(() => expect(selected()).toBe(first))
    await user.click(within(toolbar()).getByRole('button', { name: 'Reset' }))
    await waitFor(() => expect(badge(row(), 'Accepted')).toBeNull())
    expect(bar(pane)).toHaveAttribute('aria-valuenow', '0')
    expect(within(toolbar()).queryByRole('button', { name: 'Reset' })).toBeNull()
  })
})

describe('bulk review', () => {
  it('rejectAllAsksForConfirmation', async () => {
    const seen = capturePatches()
    const { user, pane } = await openField(displayOrder()[0] as string)
    const trigger = within(pane).getByRole('button', { name: 'Reject all' })
    expect(trigger).toHaveAttribute('aria-keyshortcuts', 'Alt+Shift+R')
    await user.click(trigger)
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent(`${total} fields`)

    // Esc cancels: nothing is sent, focus returns to the trigger.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(trigger).toHaveFocus()
    expect(seen).toEqual([])

    await user.click(trigger)
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: `Reject ${total} fields` }))
    await waitFor(() => expect(bar(pane)).toHaveAttribute('aria-valuenow', String(total)))
    expect(seen).toEqual([{ url: '/api/documents/D01/cases/0/fields', body: { action: 'reject' } }])
    for (const id of displayOrder()) expect(badge(rowFor(pane, fieldOf(id).label), 'Rejected')).not.toBeNull()
    expect(trigger).toHaveFocus()
  })

  it('acceptAllSkipsReviewedFields', async () => {
    const [first] = displayOrder() as [string]
    const seen = capturePatches()
    const { user, pane, row } = await openField(first)
    expect(within(pane).getByRole('button', { name: 'Accept all' })).toHaveAttribute('aria-keyshortcuts', 'Alt+Shift+A')
    await user.click(within(screen.getByRole('group', { name: `Review ${fieldOf(first).label}` })).getByRole('button', { name: 'Reject this' }))
    await waitFor(() => expect(badge(row(), 'Rejected')).not.toBeNull())

    await user.click(within(pane).getByRole('button', { name: 'Accept all' }))
    await waitFor(() => expect(bar(pane)).toHaveAttribute('aria-valuenow', String(total)))
    expect(seen.at(-1)).toEqual({ url: '/api/documents/D01/cases/0/fields', body: { action: 'accept' } })
    expect(badge(row(), 'Rejected')).not.toBeNull()
    for (const id of displayOrder().slice(1)) expect(badge(rowFor(pane, fieldOf(id).label), 'Accepted')).not.toBeNull()
    // Nothing is left to bulk-review in this case.
    expect(within(pane).getByRole('button', { name: 'Accept all' })).toHaveAttribute('aria-disabled', 'true')
  })

  it('editSurvivesBulkAccept', async () => {
    const id = displayOrder().find((x) => fieldOf(x).type === 'string' && fieldOf(x).validation?.pattern === undefined) as string
    const { user, pane, row, inspector } = await openField(id)
    await user.type(within(inspector()).getByLabelText(fieldOf(id).label), ' kept{Enter}')
    await waitFor(() => expect(badge(row(), 'Manual edit')).not.toBeNull())

    await user.click(within(pane).getByRole('button', { name: 'Accept all' }))
    await waitFor(() => expect(bar(pane)).toHaveAttribute('aria-valuenow', String(total)))
    expect(badge(row(), 'Manual edit')).not.toBeNull()
    expect(badge(row(), 'Accepted')).toBeNull()
    expect(within(row()).getByText(`${String(d01Fields()[id]?.value)} kept`)).toBeInTheDocument()
  })

  it('reviewChipsCountAndFilter', async () => {
    const [first, second] = displayOrder() as [string, string]
    const { user, pane, row } = await openField(first)
    const chip = (name: RegExp) => within(pane).getByRole('button', { name })
    expect(chip(/^Unreviewed: /)).toHaveAccessibleName(`Unreviewed: ${total}`)
    expect(chip(/^Accepted: /)).toHaveAccessibleName('Accepted: 0')
    await user.click(within(screen.getByRole('group', { name: `Review ${fieldOf(first).label}` })).getByRole('button', { name: 'Accept this' }))
    await waitFor(() => expect(chip(/^Accepted: /)).toHaveAccessibleName('Accepted: 1'))
    expect(chip(/^Unreviewed: /)).toHaveAccessibleName(`Unreviewed: ${total - 1}`)
    expect(chip(/^Rejected: /)).toHaveAccessibleName('Rejected: 0')
    expect(chip(/^Edited: /)).toHaveAccessibleName('Edited: 0')

    // Filter to accepted fields: the accepted row stays, the advanced-to row stays because it is selected.
    await user.click(chip(/^Accepted: /))
    await waitFor(() => expect(rowsIn(pane)).toHaveLength(2))
    expect(rowsIn(pane)).toEqual([row(first), row(second)])
  })
})

describe('progress', () => {
  it('headerProgressShowsReviewedOverTotal', async () => {
    const [first] = displayOrder() as [string]
    const { user, pane, inspector } = await openField(first)
    const top = screen.getByRole('banner')
    await waitFor(() => expect(bar(top)).toHaveAttribute('aria-valuenow', '0'))
    for (const root of [pane, top]) {
      expect(bar(root)).toHaveAttribute('aria-valuemin', '0')
      expect(bar(root)).toHaveAttribute('aria-valuemax', String(total))
    }
    expect(within(pane).getByText(`0 / ${total} reviewed`)).toBeInTheDocument()

    await user.click(within(inspector()).getByRole('button', { name: 'Accept this' }))
    await waitFor(() => expect(within(pane).getByText(`1 / ${total} reviewed`)).toBeInTheDocument())
    expect(bar(pane)).toHaveAttribute('aria-valuenow', '1')
    await waitFor(() => expect(bar(top)).toHaveAttribute('aria-valuenow', '1'))
    expect(within(top).getByText(`1 / ${total} reviewed`)).toBeInTheDocument()
  })

  it('noCaseDocumentShowsNothingToReview', async () => {
    renderApp('/documents/NC1')
    const top = screen.getByRole('banner')
    expect(await within(top).findByText('Nothing to review')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).toBeNull()
    const rail = panes.rail()
    const link = (await within(rail).findAllByRole('link')).find((l) => l.getAttribute('href') === '/documents/NC1') as HTMLElement
    expect(within(link).queryByRole('img', { name: /reviewed/ })).toBeNull()
  })

  it('railShowsProgressRing', async () => {
    const [first] = displayOrder() as [string]
    const { user, inspector } = await openField(first)
    const rail = panes.rail()
    const link = (id: string) => within(rail).getAllByRole('link').find((l) => l.getAttribute('href') === `/documents/${id}`) as HTMLElement
    await waitFor(() => expect(within(link('D01')).getByRole('img', { name: `0 of ${total} reviewed` })).toBeInTheDocument())
    expect(within(link('MP1')).getByRole('img', { name: `0 of ${total * 2} reviewed` })).toBeInTheDocument()

    await user.click(within(inspector()).getByRole('button', { name: 'Accept this' }))
    await waitFor(() => expect(within(link('D01')).getByRole('img', { name: `1 of ${total} reviewed` })).toBeInTheDocument())
  })
})

describe('flags', () => {
  it('imageDocumentFlagsHaveLabels', () => {
    expect(flagInfo('transcribed_from_image')?.label).toBe('Transcribed from image — verify against the PDF')
    expect(flagInfo('unsupported_format')?.label).toBe('Not processed — unsupported format')
  })
})
