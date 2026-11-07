import { render, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Catalog, CatalogField, DocumentDetail, FieldResult, FieldType } from '../src/api/types'
import { isRisky } from '../src/lib/risk'
import { editedState } from '../src/lib/status'
import { StatusBadge } from '../src/components/StatusBadge'
import { D01, MP1, catalogFixture } from './fixtures'
import { server } from './msw/server'
import { panes, renderApp } from './renderApp'

const levels = catalogFixture.confidence_levels
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const labelOf = (id: string): string => {
  const f = catalogFixture.fields.find((x) => x.id === id)
  if (!f) throw new Error(`no catalog field ${id}`)
  return f.label
}
/** Catalog fields in display order: catalog sections, then catalog field order inside each. */
const displayOrder = (catalog: Catalog = catalogFixture) =>
  catalog.sections.flatMap((s) => catalog.fields.filter((f) => f.section === s.id))

const d01Fields = (): Record<string, FieldResult> => D01.extraction.cases[0]?.fields ?? {}
/** Field ids present in D01, in catalog order (picked by position, never by name). */
const presentIds = () => catalogFixture.fields.map((f) => f.id).filter((id) => id in d01Fields())

/** D01 with its single case changed by `mutate`, served for this test. */
function serveD01(mutate: (fields: Record<string, FieldResult>) => void): DocumentDetail {
  const doc = clone(D01)
  const kase = doc.extraction.cases[0]
  if (!kase) throw new Error('D01 has no case')
  mutate(kase.fields)
  server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
  return doc
}

/** Every field row (a list item's button with aria-expanded) inside `root`, in DOM order. */
const rowsIn = (root: HTMLElement) =>
  within(root).queryAllByRole('button').filter((b) => b.hasAttribute('aria-expanded') && b.closest('li') !== null)
const rowLabels = (root: HTMLElement, candidates = catalogFixture.fields.map((f) => f.label)) =>
  rowsIn(root).map((r) => {
    const text = r.textContent ?? ''
    // longest match wins, so "Suspect drug" never shadows a longer label
    return [...candidates].sort((a, b) => b.length - a.length).find((l) => text.startsWith(l)) ?? `?${text}`
  })
const rowFor = (root: HTMLElement, label: string): HTMLElement => {
  const hit = rowsIn(root).find((r) => (r.textContent ?? '').startsWith(label))
  if (!hit) throw new Error(`no row labelled ${label}`)
  return hit
}
/** Waits until the fields pane shows rows. */
async function loadedFields(): Promise<HTMLElement> {
  const pane = panes.fields()
  await waitFor(() => expect(rowsIn(pane).length).toBeGreaterThan(0))
  return pane
}

// Radix positions open tooltips with ResizeObserver, which jsdom lacks.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= NoopResizeObserver

const scrollIntoView = vi.fn()
beforeEach(() => {
  scrollIntoView.mockClear()
  Element.prototype.scrollIntoView = scrollIntoView
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('FieldsPanel structure', () => {
  it('sectionsAndFieldsFollowCatalogOrder', async () => {
    renderApp('/documents/D01')
    const pane = await loadedFields()
    const sectionsWithFields = catalogFixture.sections.filter((s) => catalogFixture.fields.some((f) => f.section === s.id))
    const headings = within(pane).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(headings).toEqual(sectionsWithFields.map((s) => s.label))

    for (const s of sectionsWithFields) {
      const region = within(pane).getByRole('region', { name: s.label })
      const expected = catalogFixture.fields.filter((f) => f.section === s.id).map((f) => f.label)
      expect(rowLabels(region)).toEqual(expected)
    }
    expect(rowLabels(pane)).toEqual(displayOrder().map((f) => f.label))
  })

  it('addedCatalogFieldRendersWithoutCodeChange', async () => {
    const first = catalogFixture.sections[0]
    if (!first) throw new Error('catalog has no section')
    const catalog = clone(catalogFixture)
    catalog.fields.push({ id: 'zz_new', label: 'New field', section: first.id, type: 'string' })
    server.use(http.get('*/api/catalog', () => HttpResponse.json(catalog)))
    renderApp('/documents/D01')
    const pane = await loadedFields()
    const region = within(pane).getByRole('region', { name: first.label })
    await waitFor(() => expect(rowLabels(region, catalog.fields.map((f) => f.label)).at(-1)).toBe('New field'))
    const row = rowFor(region, 'New field')
    expect(row).toHaveTextContent('Not in this output')
    expect(within(row).getByRole('img', { name: 'Status: unknown' })).toBeInTheDocument()
  })

  /**
   * A catalog that gains one field of each type must work end to end with no code change: the served
   * extraction gives it a value and a valid quote, its row shows the formatted value, and selecting it
   * opens a labelled control of the type's kind.
   */
  const zedOptions = [
    { code: 'zz_a', label: 'Zed option A' },
    { code: 'zz_b', label: 'Zed option B' },
    { code: 'zz_c', label: 'Zed option C' },
  ]
  const newFieldCases: {
    type: FieldType
    value: unknown
    shown: string[]
    control: (inspector: HTMLElement, label: string) => void
  }[] = [
    {
      type: 'string',
      value: 'Zed text',
      shown: ['Zed text'],
      control: (i, l) => {
        expect(within(i).getByLabelText(l)).toHaveAttribute('type', 'text')
        expect(within(i).getByLabelText(l)).toHaveValue('Zed text')
      },
    },
    {
      type: 'integer',
      value: 42,
      shown: ['42'],
      control: (i, l) => {
        const input = within(i).getByRole('spinbutton', { name: l })
        expect(input).toHaveAttribute('step', '1')
        expect(input).toHaveValue(42)
      },
    },
    {
      type: 'number',
      value: 2.5,
      shown: ['2.5'],
      control: (i, l) => {
        const input = within(i).getByRole('spinbutton', { name: l })
        expect(input).toHaveAttribute('step', 'any')
        expect(input).toHaveValue(2.5)
      },
    },
    {
      type: 'date',
      value: '2026-03-05',
      shown: ['5 Mar 2026'],
      control: (i, l) => {
        expect(within(i).getByLabelText(l)).toHaveAttribute('type', 'date')
        expect(within(i).getByLabelText(l)).toHaveValue('2026-03-05')
      },
    },
    {
      type: 'coded',
      value: 'zz_b',
      shown: ['Zed option B'],
      control: (i, l) => expect(within(i).getByRole('combobox', { name: l })).toHaveTextContent('Zed option B'),
    },
    {
      type: 'coded_multi',
      value: ['zz_a', 'zz_c'],
      shown: ['Zed option A', 'Zed option C'],
      control: (i, l) => {
        expect(within(i).getByRole('button', { name: l })).toBeInTheDocument()
        const chips = within(i).getByRole('list', { name: `${l}: selected` })
        expect(within(chips).getAllByRole('listitem').map((li) => li.textContent?.replace(/Remove.*$/, ''))).toEqual([
          'Zed option A',
          'Zed option C',
        ])
      },
    },
    {
      type: 'string_list',
      value: ['alpha item', 'beta item'],
      shown: ['alpha item', 'beta item'],
      control: (i, l) => {
        expect(within(i).getByRole('textbox', { name: l })).toBeInTheDocument()
        const items = within(i).getByRole('list', { name: `${l}: items` })
        expect(within(items).getAllByRole('listitem').map((li) => li.textContent?.replace(/Remove.*$/, ''))).toEqual([
          'alpha item',
          'beta item',
        ])
      },
    },
  ]

  it('newFieldCasesCoverEveryCatalogType', () => {
    const types: FieldType[] = ['string', 'integer', 'number', 'date', 'coded', 'coded_multi', 'string_list']
    expect(newFieldCases.map((c) => c.type)).toEqual(types)
  })

  it.each(newFieldCases)('addedCatalogField_$type_RendersItsValueAndControlWithoutCodeChange', async ({ type, value, shown, control }) => {
    const section = catalogFixture.sections.at(-1)
    if (!section) throw new Error('catalog has no section')
    const label = `Zed new ${type} field`
    const field: CatalogField = { id: `zz_new_${type}`, label, section: section.id, type }
    if (type === 'coded' || type === 'coded_multi') field.options = zedOptions
    const catalog = clone(catalogFixture)
    catalog.fields.push(field)
    server.use(http.get('*/api/catalog', () => HttpResponse.json(catalog)))

    // A valid quote: a word of D01's text, with code-point offsets.
    const text = D01.text as string
    const word = /[A-Za-z]{5,}/.exec(text)
    if (!word) throw new Error('D01 text has no word to quote')
    const start = Array.from(text.slice(0, word.index)).length
    const quote = word[0]
    serveD01((fields) => {
      fields[field.id] = {
        status: 'extracted',
        value,
        confidence: levels[0],
        provenance: [{ quote, start, end: start + Array.from(quote).length }],
      }
    })

    const { user, router } = renderApp('/documents/D01')
    const pane = await loadedFields()
    const region = within(pane).getByRole('region', { name: section.label })
    await waitFor(() => expect(rowsIn(region).some((r) => (r.textContent ?? '').startsWith(label))).toBe(true))
    const row = rowFor(region, label)
    for (const text of shown) expect(within(row).getByText(text, { exact: false })).toBeInTheDocument()
    expect(within(row).getByRole('img', { name: `Confidence: ${levels[0] as string}` })).toBeInTheDocument()

    await user.click(row)
    expect(new URLSearchParams(router.state.location.search).get('field')).toBe(field.id)
    await waitFor(() => expect(rowFor(region, label)).toHaveAttribute('aria-expanded', 'true'))
    const inspector = document.getElementById(rowFor(region, label).getAttribute('aria-controls') ?? '') as HTMLElement
    control(inspector, label)
    // The quote is valid: it can be shown in the text.
    expect(within(inspector).getByRole('button', { name: new RegExp(`${quote}.*Show in text`) })).toBeInTheDocument()
  })

  it('catalogFieldAbsentFromOutputRendersUnknown', async () => {
    const gone = presentIds()[0] as string
    serveD01((fields) => {
      delete fields[gone]
    })
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { user, router } = renderApp('/documents/D01')
    const pane = await loadedFields()
    const row = rowFor(pane, labelOf(gone))
    expect(row).toHaveTextContent('Not in this output')
    expect(within(row).getByRole('img', { name: 'Status: unknown' })).toBeInTheDocument()
    expect(within(pane).getByRole('button', { name: /^unknown/ })).toHaveTextContent('1')

    // a reviewer can still open it: it is a catalog field, just absent from this output
    await user.click(row)
    expect(new URLSearchParams(router.state.location.search).get('field')).toBe(gone)
    await waitFor(() => expect(rowFor(pane, labelOf(gone))).toHaveAttribute('aria-expanded', 'true'))
    expect(err).not.toHaveBeenCalled()
  })

  it('caseTabsSwitchCases', async () => {
    const infant = MP1.extraction.cases[1]
    if (!infant) throw new Error('MP1 needs two cases')
    const conflictId = Object.keys(infant.fields).find((id) => infant.fields[id]?.status === 'conflict') as string
    const { user, router } = renderApp('/documents/MP1')
    const pane = await loadedFields()
    const tabs = within(pane).getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual([expect.stringContaining('Mother'), expect.stringContaining('Infant')])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    expect(rowFor(pane, labelOf(conflictId))).not.toHaveTextContent('Conflict')

    await user.click(within(pane).getByRole('tab', { name: /Infant/ }))
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('case')).toBe('1'))
    await waitFor(() => expect(within(pane).getByRole('tab', { name: /Infant/ })).toHaveAttribute('aria-selected', 'true'))
    expect(rowFor(pane, labelOf(conflictId))).toHaveTextContent('Conflict')
    expect(within(rowFor(pane, labelOf(conflictId))).getByRole('img', { name: 'Status: conflict' })).toBeInTheDocument()
  })

  it('caseTabRiskCountHasAGlyphAndALabel', async () => {
    renderApp('/documents/MP1')
    const pane = await loadedFields()
    const tab = within(pane).getByRole('tab', { name: /Infant/ })
    // Not colour alone: the same pill as the rail, "!" plus the count, named for screen readers.
    const pill = within(tab).getByRole('img', { name: /^\d+ risky values?$/ })
    expect(pill.textContent).toMatch(/^!\d+$/)
  })

  it('singleCaseHasNoTabs', async () => {
    renderApp('/documents/D01')
    const pane = await loadedFields()
    expect(within(pane).queryByRole('tablist')).toBeNull()
  })
})

describe('badges', () => {
  it('badgesCombineIconColourAndText', async () => {
    const [a, b, c, d, e] = presentIds()
    if (e === undefined || levels.length < 3) throw new Error('fixture needs five fields and three levels')
    const [high, medium, low] = levels as [string, string, string]
    serveD01((fields) => {
      fields[a as string] = { ...fields[a as string], status: 'extracted', confidence: high }
      fields[b as string] = { ...fields[b as string], status: 'extracted', confidence: medium }
      fields[c as string] = { ...fields[c as string], status: 'extracted', confidence: low }
      fields[d as string] = { status: 'unknown' }
      fields[e] = { status: 'conflict', note: 'Candidates: one; two' }
    })
    renderApp('/documents/D01')
    const pane = await loadedFields()
    const names = [`Confidence: ${high}`, `Confidence: ${medium}`, `Confidence: ${low}`, 'Status: unknown', 'Status: conflict']
    const texts = [high, medium, low, 'unknown', 'conflict']
    const ids = [a, b, c, d, e] as string[]
    const icons: string[] = []
    const tones: string[] = []
    names.forEach((name, i) => {
      const badge = within(rowFor(pane, labelOf(ids[i] as string))).getByRole('img', { name })
      expect(badge).toHaveTextContent(texts[i] as string)
      const icon = badge.querySelector('[aria-hidden="true"]')?.textContent ?? ''
      expect(icon.trim()).not.toBe('')
      icons.push(icon)
      tones.push(badge.getAttribute('data-tone') ?? '')
    })
    expect(icons).toEqual(['✓', '◐', '!', '?', '⇄'])
    expect(tones).toEqual(['ok', 'caution', 'risk', 'muted', 'conflict'])
    expect(rowFor(pane, labelOf(d as string))).toHaveTextContent('Not stated')
    expect(rowFor(pane, labelOf(e))).toHaveTextContent('Conflict')

    render(<StatusBadge state={editedState()} />)
    const edited = screen.getByRole('img', { name: 'Edited' })
    expect(edited).toHaveTextContent('✎')
    expect(edited).toHaveTextContent('edited')
    expect(edited).toHaveAttribute('data-tone', 'edited')
  })

  it('badgeToneFollowsRankNotName', async () => {
    const catalog = clone(catalogFixture)
    catalog.confidence_levels = [...catalog.confidence_levels].reverse()
    server.use(http.get('*/api/catalog', () => HttpResponse.json(catalog)))
    const first = presentIds()[0] as string
    const strongest = levels[0] as string // now the last rank
    renderApp('/documents/D01')
    const pane = await loadedFields()
    const badge = within(rowFor(pane, labelOf(first))).getByRole('img', { name: `Confidence: ${strongest}` })
    await waitFor(() => expect(badge).toHaveAttribute('data-tone', 'risk'))
  })

  it('badgeTooltipShowsTheFullNote', async () => {
    const id = presentIds()[1] as string
    serveD01((fields) => {
      fields[id] = { status: 'conflict', note: 'Candidates: 12 March 2026; 14 March 2026' }
    })
    const { user } = renderApp('/documents/D01')
    const pane = await loadedFields()
    await user.hover(within(rowFor(pane, labelOf(id))).getByRole('img', { name: 'Status: conflict' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Candidates: 12 March 2026; 14 March 2026')
  })

  it('invalidQuotePositionShowsWarningOnRow', async () => {
    const id = presentIds()[0] as string
    serveD01((fields) => {
      const f = fields[id]
      if (f) f.provenance = [{ quote: 'x', start: 5, end: 10_000_000 }]
    })
    vi.spyOn(console, 'error').mockImplementation(() => {})
    renderApp('/documents/D01')
    const pane = await loadedFields()
    await waitFor(() =>
      expect(within(rowFor(pane, labelOf(id))).getByRole('img', { name: 'Quote position outside document' })).toBeInTheDocument(),
    )
    expect(within(rowFor(pane, labelOf(presentIds()[1] as string))).queryByRole('img', { name: /outside/ })).toBeNull()
  })
})

describe('summary and filters', () => {
  /** D01 with one low, one conflict and one unknown field, plus its existing medium. */
  function serveMixed() {
    const ids = presentIds()
    const [low, conflict, unknown] = [ids[0], ids[1], ids[2]] as [string, string, string]
    const doc = serveD01((fields) => {
      fields[low] = { ...fields[low], status: 'extracted', confidence: levels[levels.length - 1] as string }
      fields[conflict] = { status: 'conflict', note: 'Candidates: a; b' }
      fields[unknown] = { status: 'unknown' }
    })
    return { doc, low, conflict, unknown }
  }

  it('summaryShowsACountPerState', async () => {
    const { doc } = serveMixed()
    renderApp('/documents/D01')
    const pane = await loadedFields()
    const results = Object.values(doc.extraction.cases[0]?.fields ?? {})
    for (const level of levels) {
      const n = results.filter((r) => r.status === 'extracted' && r.confidence === level).length
      expect(within(pane).getByRole('button', { name: new RegExp(`^${level}\\b`) })).toHaveTextContent(String(n))
    }
    expect(within(pane).getByRole('button', { name: /^unknown/ })).toHaveTextContent('1')
    expect(within(pane).getByRole('button', { name: /^conflict/ })).toHaveTextContent('1')
    expect(within(pane).queryByRole('button', { name: /^edited/ })).toBeNull()
  })

  it('riskyOnlyShowsConflictAndLowerConfidence', async () => {
    const { doc } = serveMixed()
    const { user } = renderApp('/documents/D01')
    const pane = await loadedFields()
    const fields = doc.extraction.cases[0]?.fields ?? {}
    const expected = displayOrder()
      .filter((f) => fields[f.id] !== undefined && isRisky(fields[f.id] as FieldResult, levels))
      .map((f) => f.label)
    expect(expected.length).toBeGreaterThanOrEqual(3)

    const toggle = within(pane).getByRole('button', { name: /risky only/i })
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    expect(rowLabels(pane)).toEqual(expected)

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    expect(rowLabels(pane)).toEqual(displayOrder().map((f) => f.label))
  })

  it('summaryChipFiltersList', async () => {
    const { unknown } = serveMixed()
    const { user } = renderApp('/documents/D01')
    const pane = await loadedFields()
    const chip = within(pane).getByRole('button', { name: /^unknown/ })
    expect(chip).toHaveAttribute('aria-pressed', 'false')

    await user.click(chip)
    expect(chip).toHaveAttribute('aria-pressed', 'true')
    expect(rowLabels(pane)).toEqual([labelOf(unknown)])
    // sections with no visible row are hidden entirely
    expect(within(pane).getAllByRole('heading', { level: 3 })).toHaveLength(1)
    expect(within(pane).getByText(/showing 1 of/i)).toBeInTheDocument()

    await user.click(chip)
    expect(rowLabels(pane)).toEqual(displayOrder().map((f) => f.label))
  })

  it('filtersResetWhenDocumentChanges', async () => {
    serveMixed()
    const { user } = renderApp('/documents/D01')
    const pane = await loadedFields()
    await user.click(within(pane).getByRole('button', { name: /risky only/i }))
    const mp1 = within(panes.rail()).getAllByRole('link').find((l) => l.getAttribute('href') === '/documents/MP1')
    if (!mp1) throw new Error('no MP1 link')
    await user.click(mp1)
    await waitFor(() => expect(within(pane).queryAllByRole('tab').length).toBe(2))
    expect(within(pane).getByRole('button', { name: /risky only/i })).toHaveAttribute('aria-pressed', 'false')
    expect(rowLabels(pane)).toEqual(displayOrder().map((f) => f.label))
  })
})

describe('selection', () => {
  it('clickingRowSelectsAndClickingAgainCollapses', async () => {
    const id = presentIds().find((x) => (d01Fields()[x]?.provenance?.length ?? 0) > 0) as string
    const { user, router } = renderApp('/documents/D01')
    const pane = await loadedFields()
    await user.click(rowFor(pane, labelOf(id)))
    expect(new URLSearchParams(router.state.location.search).get('field')).toBe(id)
    const row = rowFor(pane, labelOf(id))
    await waitFor(() => expect(row).toHaveAttribute('aria-expanded', 'true'))
    const inspector = document.getElementById(row.getAttribute('aria-controls') ?? '')
    expect(inspector).not.toBeNull()
    const quote = d01Fields()[id]?.provenance?.[0]?.quote as string
    expect(within(inspector as HTMLElement).getByRole('button', { name: new RegExp(quote.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })).toBeInTheDocument()

    await user.click(rowFor(pane, labelOf(id)))
    expect(new URLSearchParams(router.state.location.search).get('field')).toBeNull()
    await waitFor(() => expect(rowFor(pane, labelOf(id))).toHaveAttribute('aria-expanded', 'false'))
    expect(rowsIn(pane).filter((r) => r.getAttribute('aria-expanded') === 'true')).toHaveLength(0)
  })

  it('inspectorExcerptScrollsToItsQuote', async () => {
    const id = presentIds().find((x) => (d01Fields()[x]?.provenance?.length ?? 0) > 0) as string
    const { user } = renderApp(`/documents/D01?field=${id}`)
    const pane = await loadedFields()
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1))
    const row = rowFor(pane, labelOf(id))
    const inspector = document.getElementById(row.getAttribute('aria-controls') ?? '') as HTMLElement
    const [excerpt] = within(inspector).getAllByRole('button')
    await user.click(excerpt as HTMLElement)
    expect(scrollIntoView).toHaveBeenCalledTimes(2)
    const target = scrollIntoView.mock.contexts[1] as HTMLElement
    expect(target.classList.contains('is-selected')).toBe(true)
  })

  it('conflictInspectorShowsCandidates', async () => {
    const id = presentIds()[1] as string
    serveD01((fields) => {
      fields[id] = { status: 'conflict', note: 'Candidates: 12 March 2026; 14 March 2026' }
    })
    renderApp(`/documents/D01?field=${id}`)
    const pane = await loadedFields()
    const row = rowFor(pane, labelOf(id))
    await waitFor(() => expect(row).toHaveAttribute('aria-expanded', 'true'))
    const inspector = document.getElementById(row.getAttribute('aria-controls') ?? '') as HTMLElement
    const items = within(within(inspector).getByRole('list', { name: /candidates/i })).getAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual(['12 March 2026', '14 March 2026'])
  })

  it('inspectorWithoutATextLayerSaysSoInsteadOfOutsideTheDocument', async () => {
    const id = presentIds().find((x) => (d01Fields()[x]?.provenance?.length ?? 0) > 0) as string
    const doc = clone(D01)
    doc.text = null
    server.use(http.get('*/api/documents/D01', () => HttpResponse.json(doc)))
    renderApp(`/documents/D01?field=${id}`)
    const pane = await loadedFields()
    await waitFor(() => expect(rowFor(pane, labelOf(id))).toHaveAttribute('aria-expanded', 'true'))
    const inspector = document.getElementById(rowFor(pane, labelOf(id)).getAttribute('aria-controls') ?? '') as HTMLElement
    expect(within(inspector).getAllByText('No text layer — cannot show in text').length).toBeGreaterThan(0)
    expect(inspector).not.toHaveTextContent('outside document')
    expect(within(inspector).queryByRole('button', { name: /show in text/i })).toBeNull()
  })

  it('unknownFieldParamSelectsNoRow', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    renderApp('/documents/D01?field=not_a_field')
    const pane = await loadedFields()
    expect(rowsIn(pane).filter((r) => r.getAttribute('aria-expanded') === 'true')).toHaveLength(0)
  })

  it('fieldParamDoesNotSurviveDocumentChange', async () => {
    const id = presentIds()[0] as string
    const { user, router } = renderApp(`/documents/D01?field=${id}`)
    const pane = await loadedFields()
    await waitFor(() => expect(rowFor(pane, labelOf(id))).toHaveAttribute('aria-expanded', 'true'))
    const mp1 = within(panes.rail()).getAllByRole('link').find((l) => l.getAttribute('href') === '/documents/MP1')
    if (!mp1) throw new Error('no MP1 link')
    await user.click(mp1)
    await waitFor(() => expect(within(pane).queryAllByRole('tab').length).toBe(2))
    expect(router.state.location.search).not.toContain('field=')
    expect(rowsIn(pane).length).toBeGreaterThan(0)
    expect(rowsIn(pane).filter((r) => r.getAttribute('aria-expanded') === 'true')).toHaveLength(0)
  })
})
