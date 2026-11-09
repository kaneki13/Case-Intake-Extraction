import { useEffect, useMemo } from 'react'
import { useCatalog } from '../api/queries'
import type { DocumentDetail } from '../api/types'
import { buildQuoteModel } from '../lib/quotes'
import { caseLabel } from './CaseTabs'
import { FlagChips } from './Flags'
import { SegmentedText } from './SegmentedText'
import { Notice } from './states/Notice'
import { NoTextPanel } from './states/NoTextPanel'
import { DocumentSkeleton } from './states/Skeletons'
import styles from './DocumentTextPane.module.css'

interface Props {
  detail: DocumentDetail
  caseIndex: number
  /** The selected field (driven by the caller, so two panes can show the same field), or null. */
  fieldId: string | null
  /** Which side this is, e.g. "Document A". */
  label: string
  /** A quote was clicked: select its field. */
  onSelect?: (fieldId: string) => void
}

/**
 * One document's text with one case's quotes, driven by props rather than the URL: the duplicate
 * review shows two side by side and highlights the same field in both. Same segmentation, highlight
 * and scroll as the document view (buildQuoteModel + SegmentedText).
 */
export function DocumentTextPane({ detail, caseIndex, fieldId, label, onSelect }: Props) {
  const catalog = useCatalog().data
  const { id, title, text, extraction } = detail
  const kase = extraction.cases[caseIndex]

  const model = useMemo(
    () => (text !== null && catalog && kase ? buildQuoteModel(text, catalog, kase) : null),
    [text, catalog, kase],
  )
  const invalidCount = model?.invalid.length ?? 0
  useEffect(() => {
    if (model && invalidCount > 0) {
      console.error(`DocumentTextPane: ${invalidCount} quote position(s) outside ${id}`, model.invalid)
    }
    // Once per document and case, not per render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, caseIndex, invalidCount])

  const segments = model?.segments ?? []
  const quoted = fieldId !== null && segments.some((s) => s.fieldIds.includes(fieldId))

  let body
  if (!kase) {
    body = (
      <Notice icon="alert" heading="Case not in this document">
        {id} has no case {caseIndex + 1}, so there is nothing to compare here.
      </Notice>
    )
  } else if (text === null) {
    body = <NoTextPanel />
  } else if (!catalog) {
    body = <DocumentSkeleton />
  } else {
    body = (
      <article aria-label={`Text of ${id}`} className={styles.text}>
        <SegmentedText
          text={text}
          segments={segments}
          fieldId={fieldId}
          scrollKey={`${id}:${caseIndex}`}
          onSelect={(next) => onSelect?.(next)}
        />
      </article>
    )
  }

  return (
    <section aria-label={`${label}: ${id}`} className={styles.pane}>
      <header className={styles.header}>
        <h2 className={styles.eyebrow}>
          <span className={styles.side}>{label}</span>{' '}
          <span className={styles.id}>{id}</span>
        </h2>
        <p className={styles.title} title={title}>{title}</p>
        <div className={styles.meta}>
          {extraction.cases.length > 1 && kase && (
            <span className={styles.case}>{caseLabel(kase, caseIndex)}</span>
          )}
          <FlagChips flags={extraction.flags ?? []} />
          {invalidCount > 0 && (
            <span className={styles.warning}>
              <span aria-hidden="true">⚠ </span>
              {invalidCount} quote position{invalidCount === 1 ? '' : 's'} outside the document
            </span>
          )}
        </div>
        {fieldId !== null && kase && text !== null && catalog && !quoted && (
          <p role="status" className={styles.noQuote}>
            <span aria-hidden="true" className={styles.noQuoteIcon}>◌</span>
            <span>No quote in this document</span>
          </p>
        )}
      </header>
      {body}
    </section>
  )
}
