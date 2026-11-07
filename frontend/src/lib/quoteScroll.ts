/**
 * Scroll requests from the inspector's quote excerpts to the document text.
 *
 * Selection lives in the URL, so re-selecting the already selected field changes nothing and
 * cannot re-run the text's scroll. An excerpt instead asks for one specific quote: the text
 * (SegmentedText) listens and scrolls that quote's first segment into view.
 */
export interface QuoteScrollRequest {
  fieldId: string
  /** UTF-16 offset where the quote starts in the document text. */
  from: number
}

const bus = new EventTarget()
const EVENT = 'scroll-to-quote'

export function requestQuoteScroll(request: QuoteScrollRequest): void {
  bus.dispatchEvent(new CustomEvent<QuoteScrollRequest>(EVENT, { detail: request }))
}

/** Subscribe; returns the unsubscribe function (an effect cleanup). */
export function onQuoteScroll(handler: (request: QuoteScrollRequest) => void): () => void {
  const listener = (e: Event) => handler((e as CustomEvent<QuoteScrollRequest>).detail)
  bus.addEventListener(EVENT, listener)
  return () => bus.removeEventListener(EVENT, listener)
}
