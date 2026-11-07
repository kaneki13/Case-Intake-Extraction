import { Fragment, useEffect, useLayoutEffect, useRef } from 'react'
import { onQuoteScroll } from '../lib/quoteScroll'
import type { Segment } from '../lib/segments'

interface Props {
  text: string
  segments: Segment[]
  /** The selected field, or null. */
  fieldId: string | null
  /** Changes when the document or case changes, so the scroll re-runs. */
  scrollKey: string
  onSelect: (fieldId: string) => void
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function scrollTo(el: HTMLElement | undefined): void {
  el?.scrollIntoView({ block: 'center', behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
}

/**
 * The document text with every quote wrapped in `<span class="quote" data-fields="...">`.
 * Quotes of the selected field also get `is-selected` and `aria-current`, and the first is scrolled into view
 * (unless the selection came from clicking one of its quotes, which is already in view).
 * A quote scroll request (an inspector excerpt) scrolls that quote of the selected field instead.
 *
 * Quote spans are deliberately not focusable: the fields panel is the keyboard path, and many
 * stops inside prose would make Tab unusable. They are click targets only (mouse convenience).
 */
export function SegmentedText({ text, segments, fieldId, scrollKey, onSelect }: Props) {
  // The selected field's segments by their start offset (only selected segments register).
  const selectedAt = useRef(new Map<number, HTMLSpanElement>())
  let firstSelectedIndex = -1
  if (fieldId !== null) firstSelectedIndex = segments.findIndex((s) => s.fieldIds.includes(fieldId))
  const firstFrom = segments[firstSelectedIndex]?.from
  // The field a quote click is selecting: that quote is already under the pointer, so the selection
  // it causes must not jump the text to the field's first quote. Used by the next scroll, then cleared.
  const clickedFor = useRef<string | null>(null)

  useLayoutEffect(() => {
    const clicked = clickedFor.current
    clickedFor.current = null
    if (fieldId === null || firstFrom === undefined) return
    if (clicked === fieldId) return
    scrollTo(selectedAt.current.get(firstFrom))
    // Re-run on selection / document / case change, not on every segment rebuild.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldId, scrollKey, firstSelectedIndex >= 0])

  useEffect(
    () =>
      onQuoteScroll((request) => {
        if (request.fieldId === fieldId) scrollTo(selectedAt.current.get(request.from))
      }),
    [fieldId],
  )

  if (segments.length === 0) return <>{text}</>
  return (
    <>
      {segments.map((seg) => {
        const slice = text.slice(seg.from, seg.to)
        if (seg.fieldIds.length === 0) return slice
        const selected = fieldId !== null && seg.fieldIds.includes(fieldId)
        const click = () => {
          const ids = seg.fieldIds
          const at = fieldId === null ? -1 : ids.indexOf(fieldId)
          const next = ids[at < 0 ? 0 : (at + 1) % ids.length]
          if (next === undefined) return
          // Re-selecting the selected field changes nothing, so no scroll would consume the marker.
          if (next !== fieldId) clickedFor.current = next
          onSelect(next)
        }
        return (
          <Fragment key={`${seg.from}-${seg.to}`}>
            <span
              className={selected ? 'quote is-selected' : 'quote'}
              data-fields={seg.fieldIds.join(' ')}
              aria-current={selected ? 'true' : undefined}
              ref={
                selected
                  ? (el) => {
                      if (el) selectedAt.current.set(seg.from, el)
                      else selectedAt.current.delete(seg.from)
                    }
                  : undefined
              }
              onClick={click}
            >
              {slice}
            </span>
            {selected && <span className="visually-hidden">selected quote</span>}
          </Fragment>
        )
      })}
    </>
  )
}
