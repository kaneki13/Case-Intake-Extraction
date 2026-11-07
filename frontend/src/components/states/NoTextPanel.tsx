import { Notice } from './Notice'

/** The document has no text layer (`text: null`): nothing to read or highlight. */
export function NoTextPanel() {
  return (
    <Notice icon="fileX" heading="No text layer">
      This file has no extractable text, so there is nothing to read or highlight.
    </Notice>
  )
}
