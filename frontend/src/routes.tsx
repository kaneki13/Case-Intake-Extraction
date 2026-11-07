import { Navigate, type RouteObject } from 'react-router'
import { useDocuments } from './api/queries'
import { AppShell } from './components/AppShell'
import { DocumentView } from './components/DocumentView'
import { FieldsPanel } from './components/FieldsPanel'
import { Panes } from './components/Panes'
import { Notice } from './components/states/Notice'
import { DocumentSkeleton, FieldsSkeleton } from './components/states/Skeletons'

/** `/`: open the first document as served (the API sorts by id). */
function FirstDocument() {
  const documents = useDocuments()
  const first = documents.data?.[0]
  if (first) return <Navigate to={`/documents/${encodeURIComponent(first.id)}`} replace />
  if (documents.data) {
    return (
      <Panes
        document={<Notice icon="inbox" heading="No documents yet">Processed documents will appear in the rail.</Notice>}
        fields={null}
      />
    )
  }
  if (documents.isError) {
    // The rail shows the error and the retry; the panes only say why they are empty.
    return (
      <Panes
        document={<Notice icon="doc" heading="No document open">Choose a document once the list has loaded.</Notice>}
        fields={null}
      />
    )
  }
  return <Panes document={<DocumentSkeleton />} fields={<FieldsSkeleton />} />
}

export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <FirstDocument /> },
      { path: 'documents/:id', element: <Panes document={<DocumentView />} fields={<FieldsPanel />} /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]
