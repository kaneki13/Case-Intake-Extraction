import { useId, useLayoutEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { errorMessage } from '../api/client'
import type { CatalogField, FieldResult, Provenance, Review } from '../api/types'
import { buildCodePointIndex, toUtf16 } from '../lib/offsets'
import { requestQuoteScroll } from '../lib/quoteScroll'
import { conflictCandidates } from '../lib/status'
import { sameValue, type EditValue } from '../lib/values'
import { EDITOR_ATTR } from '../hooks/useFieldKeyboard'
import { FieldControl } from './controls/FieldControl'
import type { EditStart } from './controls/types'
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
  /** A keyboard request to start editing (Enter or a typed character on the row), or null. */
  start: EditStart | null
  /** Called once `start` has been handed to the control. */
  onStarted: () => void
  /** Closes the editor: focus goes back to the row. */
  onClose: () => void
}

/**
 * The inline region under a selected row: the catalog-driven control with Save, the review buttons,
 * every quote as a clickable excerpt, then the note or the conflict candidates.
 *
 * The control and Save form the editor. Inside it, Enter on the control saves a changed value and
 * closes an unchanged one (a closed option list or multi-select opens on Enter instead), and Esc
 * cancels: the shown value comes back, nothing is recorded, and focus returns to the row.
 */
export function FieldInspector({
  id, field, result, review, text, invalid, modelText, onSave, onReview, busy, actionError, start, onStarted, onClose,
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
  // Bumped by a cancel: the control starts afresh, dropping text it held back (e.g. not yet valid).
  const [generation, setGeneration] = useState(0)
  const formRef = useRef<HTMLFormElement>(null)
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
  // The control (child) takes the start in its layout effect first; then it is spent.
  useLayoutEffect(() => {
    if (start) onStarted()
    // Only a new start request acts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start])

  const commit = () => {
    // A control holding text it cannot accept (it says why) keeps its last valid value: never save that.
    const bad = formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')
    if (bad) {
      bad.focus()
      return
    }
    if (unchanged) {
      onClose()
      return
    }
    if (saving || busy) return
    setSaving(true)
    onSave(value as EditValue)
      .then(() => {
        // Saved: the editor closes (the panel may already have moved on to the next field).
        if (formRef.current?.contains(document.activeElement)) onClose()
      })
      .catch((error: unknown) => {
        setSaveError(errorMessage(error))
        document.getElementById(controlId)?.focus()
      })
      .finally(() => setSaving(false))
  }
  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    commit()
  }
  const cancel = () => {
    onClose()
    setValue(current)
    setSaveError(null)
    setGeneration((g) => g + 1)
  }
  // Capture phase: ahead of the control's own Enter (a closed list would open, a button would click).
  const onEnter = (event: KeyboardEvent<HTMLFormElement>) => {
    if (event.key !== 'Enter' || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
    const target = event.target as HTMLElement
    if (target.id !== controlId) return
    // The add box of a list adds its text first; Enter on an empty box saves.
    if (field.type === 'string_list' && target instanceof HTMLInputElement && target.value.trim() !== '') return
    // A closed option list or multi-select with nothing changed opens on Enter.
    const picker = field.type === 'coded' || field.type === 'coded_multi'
    if (picker && unchanged) return
    event.preventDefault()
    commit()
  }
  const onEscape = (event: KeyboardEvent<HTMLFormElement>) => {
    // An open list or popover closes first (it handles Esc before this).
    if (event.key !== 'Escape' || event.defaultPrevented) return
    event.preventDefault()
    cancel()
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
        <form
          ref={formRef}
          className={styles.form}
          noValidate
          onSubmit={save}
          onKeyDownCapture={onEnter}
          onKeyDown={onEscape}
          aria-describedby={saveError ? errorId : undefined}
          {...{ [EDITOR_ATTR]: '' }}
        >
          <FieldControl key={generation} id={controlId} field={field} value={value} onChange={change} start={start} />
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
            <span className={styles.hint}>{unchanged ? 'Change the value to save an edit' : 'Enter saves · Esc cancels'}</span>
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
