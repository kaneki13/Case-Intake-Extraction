import { useId, type ReactNode, type Ref } from 'react'
import { NOT_IN_OUTPUT, type FieldRowModel } from '../lib/fieldRows'
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
}

/** One compact (36px) row: label, formatted value, status badge. A disclosure button for the inspector. */
export function FieldRow({ row, selected, buttonRef, quoteOutside, onToggle, inspector }: Props) {
  const inspectorId = useId()
  const noteId = useId()
  const { field, value, state } = row
  const note = row.result === undefined ? NOT_IN_OUTPUT : row.result.note

  return (
    <li className={styles.item} data-selected={selected || undefined}>
      <button
        ref={buttonRef}
        type="button"
        className={styles.row}
        data-tone={state.tone}
        aria-expanded={selected}
        aria-controls={selected ? inspectorId : undefined}
        aria-describedby={note ? noteId : undefined}
        onClick={onToggle}
      >
        <span className={styles.label}>{field.label}</span>
        <span
          className={styles.value}
          data-kind={value.kind}
          data-edited={row.model ? true : undefined}
          title={value.kind === 'value' ? value.title : undefined}
        >
          {value.kind === 'value' && value.items ? (
            <span className={styles.chips}>
              {value.items.map((item, i) => (
                <span key={`${i}-${item}`} className={styles.chip}>{item}</span>
              ))}
            </span>
          ) : (
            value.text
          )}
        </span>
        <span className={styles.status}>
          {row.model && <span className={styles.model}>{`model: ${row.model.state.text}`}</span>}
          {quoteOutside && (
            <Tip label={OUTSIDE}>
              <span role="img" aria-label={OUTSIDE} className={styles.warn}>⚠</span>
            </Tip>
          )}
          <StatusBadge state={state} note={note} />
        </span>
      </button>
      {note && <span id={noteId} className="visually-hidden">{note}</span>}
      {selected && inspector(inspectorId)}
    </li>
  )
}
