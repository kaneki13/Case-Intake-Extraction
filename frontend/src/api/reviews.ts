import { useMutation, useQueryClient } from '@tanstack/react-query'
import { sendJson } from './client'
import type { BulkAction, MergedField, ReviewAction } from './types'

export interface ReviewFieldInput { caseIndex: number; fieldId: string; action: ReviewAction; value?: unknown }
export interface BulkReviewInput { caseIndex: number; action: BulkAction; fieldIds?: string[] }

const casePath = (docId: string, caseIndex: number) =>
  `/api/documents/${encodeURIComponent(docId)}/cases/${encodeURIComponent(String(caseIndex))}/fields`

function useInvalidate(docId: string) {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['document', docId] }),
      client.invalidateQueries({ queryKey: ['documents'] }),
    ])
}

/** Accept, reject, edit or reset one field. `value` is sent for edit only. */
export function useReviewField(docId: string) {
  const invalidate = useInvalidate(docId)
  return useMutation({
    mutationFn: ({ caseIndex, fieldId, action, value }: ReviewFieldInput) =>
      sendJson<MergedField>(
        'PATCH',
        `${casePath(docId, caseIndex)}/${encodeURIComponent(fieldId)}`,
        action === 'edit' ? { action, value } : { action },
      ),
    onSuccess: invalidate,
  })
}

/** Accept or reject the unreviewed fields of a case: all, or the listed `fieldIds`. */
export function useBulkReview(docId: string) {
  const invalidate = useInvalidate(docId)
  return useMutation({
    mutationFn: ({ caseIndex, action, fieldIds }: BulkReviewInput) =>
      sendJson<MergedField[]>(
        'PATCH',
        casePath(docId, caseIndex),
        fieldIds === undefined ? { action } : { action, fieldIds },
      ),
    onSuccess: invalidate,
  })
}
