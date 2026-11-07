import { ApiError } from '../../api/client'
import { Icon } from '../Icon'
import styles from './ErrorCard.module.css'

/** The server's problem+json detail (or status text), else the error message. */
function describe(error: unknown): { detail: string; status: string | null } {
  if (error instanceof ApiError) {
    return { detail: error.detail, status: error.status > 0 ? `HTTP ${error.status}` : null }
  }
  if (error instanceof Error) return { detail: error.message, status: null }
  return { detail: String(error), status: null }
}

interface Props {
  /** What failed, as a heading: "Couldn't load this document". */
  title: string
  error: unknown
  onRetry: () => void
  retrying?: boolean
  compact?: boolean
}

/** Inline, announced error with the server's message and a retry that refetches. */
export function ErrorCard({ title, error, onRetry, retrying = false, compact = false }: Props) {
  const { detail, status } = describe(error)
  return (
    <div role="alert" className={styles.card} data-compact={compact || undefined}>
      <span className={styles.icon}>
        <Icon name="alert" size={18} />
      </span>
      <div className={styles.body}>
        <p className={styles.title}>{title}</p>
        <p className={styles.detail}>{detail}</p>
        <div className={styles.actions}>
          <button type="button" className={styles.retry} onClick={onRetry} disabled={retrying}>
            <Icon name="retry" size={14} className={retrying ? styles.spin : undefined} />
            {retrying ? 'Retrying…' : 'Retry'}
          </button>
          {status && <span className={styles.status}>{status}</span>}
        </div>
      </div>
    </div>
  )
}
