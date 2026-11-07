import { useEffect } from 'react'
import { useDocument } from '../api/queries'
import { caseText } from '../lib/format'
import { useQuoteModel } from '../hooks/useQuoteModel'
import { SegmentedText } from './SegmentedText'
import { useSelection } from '../hooks/useSelection'
import { FlagChips } from './Flags'
import { ErrorCard } from './states/ErrorCard'
import { NoTextPanel } from './states/NoTextPanel'
import { DocumentSkeleton } from './states/Skeletons'
import styles from './DocumentView.module.css'

/**
 * Centre pane: the document header and its text as a readable document, with every quote
 * underlined and the selected field's quotes highlighted (SegmentedText).
 */
export function DocumentView() {
  const { docId, caseIndex, fieldId, select } = useSelection()
  const doc = useDocument(docId)

  const { segments, invalid, selectedFieldId, unknownField } = useQuoteModel()
  const invalidCount = invalid.length
  // Logged here only (the hook is shared with the inspector): once per document/case, not per render.
  useEffect(() => {
    if (invalidCount > 0) console.error(`DocumentView: ${invalidCount} quote position(s) outside the document`, invalid)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId, caseIndex, invalidCount])
  useEffect(() => {
    if (unknownField) console.error(`DocumentView: ?field=${fieldId} is not a catalog field; nothing selected`)
  }, [unknownField, fieldId])

  if (doc.isError) {
    return (
      <ErrorCard
        title="Couldn't load this document"
        error={doc.error}
        retrying={doc.isFetching}
        onRetry={() => void doc.refetch()}
      />
    )
  }
  if (!doc.data) return <DocumentSkeleton />

  const { id, title, text, extraction } = doc.data
  return (
    <div className={styles.column}>
      <div className={styles.header}>
        <p className={styles.eyebrow}>{id}</p>
        <h1 className={styles.title}>{title}</h1>
        <div className={styles.meta}>
          <span className={styles.cases}>{caseText(extraction.cases.length)}</span>
          <FlagChips flags={extraction.flags ?? []} />
          {invalidCount > 0 && (
            <span
              role="status"
              aria-label={`${invalidCount} quote position${invalidCount === 1 ? '' : 's'} outside the document`}
              className={styles.warning}
            >
              <span aria-hidden="true">⚠ </span>
              {invalidCount} quote position{invalidCount === 1 ? '' : 's'} outside the document
            </span>
          )}
        </div>
      </div>
      {text === null ? (
        <NoTextPanel />
      ) : (
        <article aria-label="Document text" className={styles.text}>
          <SegmentedText
            text={text}
            segments={segments}
            fieldId={selectedFieldId}
            scrollKey={`${id}:${caseIndex}`}
            // A quote click also brings the field's row into view in the fields panel.
            onSelect={(id) => select(id, { reveal: 'scroll' })}
          />
        </article>
      )}
    </div>
  )
}
