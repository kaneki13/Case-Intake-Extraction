export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly detail: string
  constructor(status: number, title: string, detail: string) {
    super(`${title}: ${detail}`)
    this.name = 'ApiError'
    this.status = status
    this.title = title
    this.detail = detail
  }
}

const isObject = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)

/** Light top-level shape check, chosen by endpoint. Returns a description of what is wrong, or null. */
function shapeProblem(path: string, body: unknown): string | null {
  const p = (path.split('?')[0] ?? path).replace(/\/+$/, '')
  if (p === '/api/catalog') {
    return isObject(body) && Array.isArray(body.fields) ? null : 'catalog without a "fields" array'
  }
  if (p === '/api/documents') {
    return Array.isArray(body) ? null : 'document list is not an array'
  }
  if (/^\/api\/documents\/[^/]+$/.test(p)) {
    const ok =
      isObject(body) &&
      typeof body.id === 'string' &&
      isObject(body.extraction) &&
      Array.isArray(body.extraction.cases)
    return ok ? null : 'document without "id" and "extraction.cases" array'
  }
  return null
}

async function toApiError(res: Response): Promise<ApiError> {
  const type = res.headers.get('content-type') ?? ''
  if (type.includes('json')) {
    try {
      const body: unknown = await res.json()
      if (isObject(body)) {
        const title = typeof body.title === 'string' ? body.title : res.statusText || `HTTP ${res.status}`
        const detail = typeof body.detail === 'string' ? body.detail : res.statusText || `HTTP ${res.status}`
        return new ApiError(res.status, title, detail)
      }
    } catch {
      // fall through to the statusText error below
    }
  }
  return new ApiError(res.status, res.statusText || `HTTP ${res.status}`, res.statusText || `HTTP ${res.status}`)
}

export async function getJson<T>(path: string, init?: RequestInit): Promise<T> {
  // Absolute URL so fetch also works outside a browser (tests); same origin as the page.
  const url = typeof location !== 'undefined' ? new URL(path, location.href).toString() : path
  let res: Response
  try {
    res = await fetch(url, init)
  } catch (cause) {
    // An aborted request is the caller's decision, not a network failure.
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    const msg = cause instanceof Error ? cause.message : String(cause)
    throw new ApiError(0, 'Network error', `Network error: could not reach the server (${msg})`)
  }
  if (!res.ok) throw await toApiError(res)
  // A success status with the wrong body: status 0, since no HTTP status describes the problem.
  let body: unknown
  try {
    body = await res.json()
  } catch {
    throw new ApiError(0, `Unexpected response shape for ${path}`, 'Unexpected response shape: the body is not valid JSON')
  }
  const problem = shapeProblem(path, body)
  if (problem) {
    throw new ApiError(0, `Unexpected response shape for ${path}`, `Unexpected response shape: ${problem}`)
  }
  return body as T // structurally checked above (top level only)
}
