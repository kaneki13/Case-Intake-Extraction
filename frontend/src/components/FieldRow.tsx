import { useId, type ReactNode, type Ref } from 'react'
import { NOT_IN_OUTPUT, type FieldRowModel, type RowValue } from '../lib/fieldRows'
import { reviewState } from '../lib/status'
import { StatusBadge } from './StatusBadge'
import { Tip } from './Tip'
import styles from './FieldRow.module.css'

const OUTSIDE = 'Quote position outside document'

interface Props {
  row: FieldRowModel
  selected: boolean
  /** The row's button (the selected row's, for revealing and focusing it). */
  buttonRef?: Ref<HTMLButtonElement>
  /** Some of this field's quotes point outside the document. */
  quoteOutside: boolean
  /** Select this row, or collapse it when it is already selected. */
  onToggle: () => void
  /** The inspector, rendered under the row while it is selected; it must carry `inspectorId`. */
  inspector: (inspectorId: string) => ReactNode
  /** The review buttons shown on the selected row, beside its badges. */
  actions?: ReactNode
}

/** A value as text, or as chips for list types. */
function Shown({ value }: { value: RowValue }) {
  if (value.kind === 'value' && value.items) {
    return (
      <span className={styles.chips}>
        {value.items.map((item, i) => (
          <span key={`${i}-${item}`} className={styles.chip}>{item}</span>
        ))}
      </span>
    )
  }
  return <>{value.text}</>
}

/**
 * One compact (36px) row: label, value, badges. A disclosure button for the inspector.
 * A reviewed row adds its review badge (✓ Accepted, ⊘ Rejected, ✎ Manual edit) and keeps the model's
 * state as a smaller badge. A rejected value is struck through; a manual edit shows the reviewer's value
 * on a blue ground with the model's value struck through beneath it.
 */
export function FieldRow({ row, selected, buttonRef, quoteOutside, onToggle, inspector, actions }: Props) {
  const inspectorId = useId()
  const noteId = useId()
  const { field, value, state, reviewKind, edited } = row
  const note = row.result === undefined ? NOT_IN_OUTPUT : row.result.note
  const reviewed = reviewKind !== 'unreviewed'
  const review = reviewed ? reviewState(reviewKind) : null
  // The leading marker follows the review once there is one: a reviewed risk needs no more attention.
  const tone = review ? review.tone : state.tone
  const titleOf = (v: RowValue) => (v.kind === 'value' ? v.title : undefined)

  return (
    <li
      className={styles.item}
      data-selected={selected || undefined}
      data-actions={selected && actions ? (row.review ? 3 : 2) : undefined}
    >
      <button
        ref={buttonRef}
        type="button"
        className={styles.row}
        data-tone={tone}
        aria-expanded={selected}
        aria-controls={selected ? inspectorId : undefined}
        aria-describedby={note ? noteId : undefined}
        onClick={onToggle}
      >
        <span className={styles.label}>{field.label}</span>
        <span className={styles.cell}>
          {edited ? (
            <>
              <span className={styles.value} data-kind={edited.kind} data-edited title={titleOf(edited)}>
                <Shown value={edited} />
              </span>
              <span className={styles.modelBelow} title={titleOf(value)}>
                <span className="visually-hidden">Model value: </span>
                <s>{value.text}</s>
              </span>
            </>
          ) : (
            <span
              className={styles.value}
              data-kind={value.kind}
              data-rejected={reviewKind === 'rejected' || undefined}
              title={titleOf(value)}
            >
              {reviewKind === 'rejected' ? (
                <s>
                  <Shown value={value} />
                </s>
              ) : (
                <Shown value={value} />
              )}
            </span>
          )}
        </span>
        <span className={styles.status}>
          {quoteOutside && (
            <Tip label={OUTSIDE}>
              <span role="img" aria-label={OUTSIDE} className={styles.warn}>⚠</span>
            </Tip>
          )}
          {review && <StatusBadge state={review} />}
          <StatusBadge state={state} note={note} small={reviewed} />
        </span>
      </button>
      {selected && actions && <div className={styles.actions}>{actions}</div>}
      {note && <span id={noteId} className="visually-hidden">{note}</span>}
      {selected && inspector(inspectorId)}
    </li>
  )
}
