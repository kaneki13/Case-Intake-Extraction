import { useId, useMemo, useState, type FormEvent } from 'react'
import { errorMessage } from '../api/client'
import type { CatalogField, FieldResult, Provenance, Review } from '../api/types'
import { buildCodePointIndex, toUtf16 } from '../lib/offsets'
import { requestQuoteScroll } from '../lib/quoteScroll'
import { conflictCandidates } from '../lib/status'
import { sameValue, type EditValue } from '../lib/values'
import { FieldControl } from './controls/FieldControl'
import { Icon } from './Icon'
import { ReviewButtons, type FieldAction } from './ReviewButtons'
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
  /** The stored review of this field, or null. */
  review: Review | null
  /** The document text, for turning quote offsets (code points) into text positions; null without a text layer. */
  text: string | null
  /** This field's provenance spans that lie outside the document (from useQuoteModel().invalidByField). */
  invalid: { start: number; end: number }[]
  /** The model's value as the row shows it, repeated (struck through) beside a manual edit. */
  modelText: string
  /** Saves `value` as a manual edit; rejects with the server's error, which the inspector shows inline. */
  onSave: (value: EditValue) => Promise<unknown>
  /** Accept, reject or reset this field. */
  onReview: (action: FieldAction) => void
  /** A review of this field is being saved. */
  busy: boolean
  /** Why the last accept, reject or reset of this field failed, or null. */
  actionError: string | null
}

/**
 * The inline region under a selected row: the catalog-driven control with Save (Enter inside the
 * control saves too), the review buttons, every quote as a clickable excerpt, then the note or the
 * conflict candidates.
 */
export function FieldInspector({
  id, field, result, review, text, invalid, modelText, onSave, onReview, busy, actionError,
}: FieldInspectorProps) {
  const fieldId = field.id
  const controlId = useId()
  const errorId = useId()
  const edited = review?.state === 'edited'
  // Unknown, conflicting and missing fields have no model value: the control starts empty.
  const modelValue = result?.status === 'extracted' ? result.value : null
  // What the field holds now: the saved edit, else the model's value.
  const current = edited ? review.value : modelValue

  // The value in the control; it follows the saved value when that changes (a save, a reset).
  const [value, setValue] = useState<unknown>(current)
  const [seen, setSeen] = useState<unknown>(current)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  if (!sameValue(field, seen, current)) {
    setSeen(current)
    setValue(current)
    setSaveError(null)
  }
  const unchanged = sameValue(field, value, current)

  const change = (next: EditValue) => {
    setValue(next)
    setSaveError(null)
  }
  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    // A control holding text it cannot accept (it says why) keeps its last valid value: never save that.
    const bad = event.currentTarget.querySelector<HTMLElement>('[aria-invalid="true"]')
    if (bad) {
      bad.focus()
      return
    }
    if (unchanged || saving || busy) return
    setSaving(true)
    onSave(value as EditValue)
      .catch((error: unknown) => {
        setSaveError(errorMessage(error))
        document.getElementById(controlId)?.focus()
      })
      .finally(() => setSaving(false))
  }

  const index = useMemo(() => (text === null ? null : buildCodePointIndex(text)), [text])
  const quotes = result?.provenance ?? []
  const isInvalid = (p: Provenance) => invalid.some((s) => s.start === p.start && s.end === p.end)
  const candidates = result?.status === 'conflict' ? conflictCandidates(result.note) : null

  return (
    <div id={id} role="region" aria-label="Field details" className={styles.inspector}>
      <div className={styles.editor} data-edited={edited || undefined}>
        {edited && (
          <p className={styles.editNote}>
            <span aria-hidden="true" className={styles.editIcon}>✎</span>
            <span className={styles.editLabel}>Manual edit</span>
            <span className={styles.modelValue}>
              Model value: <s>{modelText}</s>
            </span>
          </p>
        )}
        {/* noValidate: the controls show their own messages, and the save handler refuses invalid input. */}
        <form className={styles.form} noValidate onSubmit={save} aria-describedby={saveError ? errorId : undefined}>
          <FieldControl id={controlId} field={field} value={value} onChange={change} />
          {saveError && (
            <p id={errorId} role="alert" className={styles.saveError}>
              <span aria-hidden="true">! </span>
              Not saved: {saveError}
            </p>
          )}
          <div className={styles.formActions}>
            <button
              type="submit"
              className={styles.save}
              disabled={unchanged}
              aria-disabled={saving || busy || undefined}
            >
              <Icon name="check" size={14} />
              Save
            </button>
            <span className={styles.hint}>{unchanged ? 'Change the value to save an edit' : 'Enter saves'}</span>
          </div>
        </form>
        <ReviewButtons variant="full" label="Review this field" review={review} busy={busy || saving} onAction={onReview} />
        {actionError && (
          <p role="alert" className={styles.saveError}>
            <span aria-hidden="true">! </span>
            Review not saved: {actionError}
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
