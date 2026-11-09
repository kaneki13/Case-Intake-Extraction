import { Outlet, useMatch } from 'react-router'
import { useCatalog, useDocuments } from '../api/queries'
import { DocumentRail } from './DocumentRail'
import { Icon } from './Icon'
import { ProgressBar } from './ProgressBar'
import { ThemeToggle } from './ThemeToggle'
import styles from './AppShell.module.css'

function CatalogVersion() {
  const { data } = useCatalog()
  if (!data) return null
  return <span className={styles.version}>Catalog {data.catalog_version}</span>
}

/**
 * The open document's review progress; "Nothing to review" for a document without a case. Read from
 * the document list the rail already holds (reviews refresh it), so the top bar adds no request.
 */
function CurrentProgress() {
  const id = useMatch('/documents/:id')?.params.id
  const { data } = useDocuments()
  const doc = id === undefined ? undefined : data?.find((d) => d.id === id)
  if (!doc) return null
  return (
    <span className={styles.progress}>
      <span className={styles.progressDoc}>{doc.id}</span>
      <ProgressBar progress={doc.progress} label={`${doc.id} review progress`} />
    </span>
  )
}

/** The frame: a slim top bar, the document rail, and the routed document + fields panes. */
export function AppShell() {
  return (
    <div className={styles.app}>
      <a href="#document" className={styles.skip}>
        Skip to document
      </a>
      <header className={styles.topbar}>
        <span className={styles.brand}>
          <span className={styles.mark}>
            <Icon name="doc" size={16} />
          </span>
          <span className={styles.product}>Case review</span>
        </span>
        <span className={styles.divider} aria-hidden="true" />
        <span className={styles.context}>Adverse event intake</span>
        <span className={styles.spacer} />
        <CurrentProgress />
        <CatalogVersion />
        <ThemeToggle />
      </header>
      <div className={styles.panes}>
        <DocumentRail />
        <Outlet />
      </div>
    </div>
  )
}
