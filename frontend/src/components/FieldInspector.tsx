import { useId, useMemo } from 'react'
import type { CatalogField, FieldResult, Provenance } from '../api/types'
import { sameValue, useDrafts, type DraftValue } from '../lib/drafts'
import { buildCodePointIndex, toUtf16 } from '../lib/offsets'
import { requestQuoteScroll } from '../lib/quoteScroll'
import { conflictCandidates } from '../lib/status'
import { FieldControl } from './controls/FieldControl'
import { Icon } from './Icon'
import styles from './FieldInspector.module.css'

const OUTSIDE_DOCUMENT = 'Quote position outside document'
const NO_TEXT_LAYER = 'No text layer — cannot show in text'

export interface FieldInspectorProps {
  /** DOM id; the row's aria-controls points here. */
  id: string
  /** The catalog field: its type picks the control; its label, options and validation configure it. */
  field: CatalogField
  /** The model's result; undefined when the catalog field is absent from this output. */
  result: FieldResult | undefined
  /** The document text, for turning quote offsets (code points) into text positions; null without a text layer. */
  text: string | null
  /** This field's provenance spans that lie outside the document (from useQuoteModel().invalidByField). */
  invalid: { start: number; end: number }[]
  /** This field's draft key (`draftKey(doc, case, field)`). */
  draftKey: string
  /** The model's value as the row shows it, repeated beside a draft. */
  modelText: string
}

/**
 * The inline region under a selected row: the catalog-driven control (a local draft once edited),
 * every quote as a clickable excerpt, then the note or the conflict candidates.
 */
export function FieldInspector({ id, field, result, text, invalid, draftKey, modelText }: FieldInspectorProps) {
  const fieldId = field.id
  const controlId = useId()
  const drafts = useDrafts()
  const edited = drafts.has(draftKey)
  // Unknown, conflicting and missing fields have no model value: the control starts empty.
  const modelValue = result?.status === 'extracted' ? result.value : null
  const value = edited ? drafts.get(draftKey) : modelValue
  const change = (next: DraftValue) => {
    // Editing back to the model's value is no edit.
    if (sameValue(field, next, modelValue)) drafts.reset(draftKey)
    else drafts.set(draftKey, next)
  }
  const reset = () => {
    // The reset button disappears with the draft: keep focus in the inspector, on the control.
    document.getElementById(controlId)?.focus()
    drafts.reset(draftKey)
  }

  const index = useMemo(() => (text === null ? null : buildCodePointIndex(text)), [text])
  const quotes = result?.provenance ?? []
  const isInvalid = (p: Provenance) => invalid.some((s) => s.start === p.start && s.end === p.end)
  const candidates = result?.status === 'conflict' ? conflictCandidates(result.note) : null

  return (
    <div id={id} role="region" aria-label="Field details" className={styles.inspector}>
      <div className={styles.editor} data-edited={edited || undefined}>
        {edited && (
          <div className={styles.draftBar}>
            <p role="status" className={styles.draftNote}>
              <span aria-hidden="true" className={styles.draftIcon}>✎</span>
              Edits are not saved yet
            </p>
            <button type="button" className={styles.reset} onClick={reset}>
              <Icon name="undo" size={14} />
              Reset to model value
            </button>
          </div>
        )}
        <FieldControl id={controlId} field={field} value={value} onChange={change} />
        {edited && (
          <p className={styles.modelValue}>
            Model value: <span>{modelText}</span>
          </p>
        )}
      </div>

      {result === undefined ? (
        <p className={styles.missing}>
          <strong>Not in this output.</strong> The catalog defines this field, but this extraction has no entry for
          it, usually because it was produced under an older catalog.
        </p>
      ) : (
        <>
          <div className={styles.block}>
            <p className={styles.kicker} id={`${id}-quotes`}>
              {quotes.length === 1 ? 'Source quote' : 'Source quotes'}
            </p>
            {quotes.length === 0 ? (
              <p className={styles.empty}>No quote in the document backs this field.</p>
            ) : (
              <ul className={styles.quotes} aria-labelledby={`${id}-quotes`}>
                {quotes.map((p, i) => (
                  <li key={`${p.start}-${p.end}-${i}`}>
                    {index === null || isInvalid(p) ? (
                      <span className={styles.excerpt} data-invalid="true">
                        <span className={styles.quoteText}>“{p.quote}”</span>
                        <span className={styles.invalidNote}>
                          <span aria-hidden="true">⚠ </span>
                          {index === null ? NO_TEXT_LAYER : OUTSIDE_DOCUMENT}
                        </span>
                      </span>
                    ) : (
                      <button
                        type="button"
                        className={styles.excerpt}
                        onClick={() => requestQuoteScroll({ fieldId, from: toUtf16(index, p.start) })}
                      >
                        <span className={styles.quoteText}>“{p.quote}”</span>
                        <span className={styles.jump}>
                          <Icon name="fileText" size={14} />
                          <span className="visually-hidden">Show in text</span>
                        </span>
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>

          {candidates ? (
            <div className={styles.block}>
              <p className={styles.kicker} id={`${id}-candidates`}>Candidates</p>
              <ul className={styles.candidates} aria-labelledby={`${id}-candidates`}>
                {candidates.map((c, i) => (
                  <li key={`${i}-${c}`} className={styles.candidate}>{c}</li>
                ))}
              </ul>
            </div>
          ) : (
            result.note && (
              <div className={styles.block}>
                <p className={styles.kicker}>Model note</p>
                <p className={styles.note}>{result.note}</p>
              </div>
            )
          )}
          {result.status === 'unknown' && !result.note && (
            <p className={styles.empty}>The document does not state this.</p>
          )}
        </>
      )}
    </div>
  )
}
