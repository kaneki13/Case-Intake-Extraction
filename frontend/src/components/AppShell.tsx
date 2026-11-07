import { Outlet } from 'react-router'
import { useCatalog } from '../api/queries'
import { DraftsProvider } from '../lib/drafts'
import { DocumentRail } from './DocumentRail'
import { Icon } from './Icon'
import { ThemeToggle } from './ThemeToggle'
import styles from './AppShell.module.css'

function CatalogVersion() {
  const { data } = useCatalog()
  if (!data) return null
  return <span className={styles.version}>Catalog {data.catalog_version}</span>
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
        <CatalogVersion />
        <ThemeToggle />
      </header>
      {/* Local drafts live for the session, across documents and cases; nothing is saved yet. */}
      <DraftsProvider>
        <div className={styles.panes}>
          <DocumentRail />
          <Outlet />
        </div>
      </DraftsProvider>
    </div>
  )
}
