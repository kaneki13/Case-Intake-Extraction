import styles from './Control.module.css'

/**
 * A control's inline validity message (icon + colour + text), linked from the control by aria-describedby.
 * An alert, so it is announced as it appears while the reviewer types.
 */
export function FieldMessage({ id, children }: { id: string; children: string }) {
  return (
    <p id={id} role="alert" className={styles.error}>
      <span aria-hidden="true">! </span>
      {children}
    </p>
  )
}
