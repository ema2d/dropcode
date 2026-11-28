import type { components } from './schema'

type Schemas = components['schemas']
export type CreateShareRequest = Schemas['CreateShareRequest']
export type UploadTarget = Required<Schemas['UploadTarget']>
export type CreateShareResponse = { code: string; upload: UploadTarget }
export type CompleteShareResponse = Required<Schemas['CompleteShareResponse']>
export type ShareInfo = Schemas['ShareInfoResponse']
export type DownloadResponse = Schemas['DownloadResponse']

export const API_URL: string = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/$/, '')

/** Error codes the backend puts in every ProblemDetail (see backend common/Problems). */
export type ErrorCode =
  | 'SHARE_NOT_FOUND'
  | 'INVALID_REQUEST'
  | 'WRONG_PASSWORD'
  | 'UPLOAD_NOT_FINISHED'
  | 'TRY_AGAIN'
  | 'RATE_LIMITED'
  | 'CAPTCHA_REQUIRED'
  | 'NETWORK'
  | 'UNKNOWN'

export class ApiError extends Error {
  readonly status: number
  readonly code: ErrorCode
  readonly retryAfterSeconds?: number

  constructor(status: number, code: ErrorCode, message: string, retryAfterSeconds?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.retryAfterSeconds = retryAfterSeconds
  }
}

const KNOWN_CODES: ReadonlySet<string> = new Set([
  'SHARE_NOT_FOUND', 'INVALID_REQUEST', 'WRONG_PASSWORD', 'UPLOAD_NOT_FINISHED',
  'TRY_AGAIN', 'RATE_LIMITED', 'CAPTCHA_REQUIRED',
])

export async function toApiError(response: Response): Promise<ApiError> {
  let body: { code?: string; detail?: string; retryAfterSeconds?: number } = {}
  try {
    body = await response.json()
  } catch {
    // not JSON (e.g. proxy error page)
  }
  const code: ErrorCode = body.code && KNOWN_CODES.has(body.code)
    ? (body.code as ErrorCode)
    : response.status === 400 ? 'INVALID_REQUEST' : 'UNKNOWN'
  const retryHeader = Number(response.headers.get('Retry-After'))
  const retryAfter = body.retryAfterSeconds ?? (Number.isFinite(retryHeader) && retryHeader > 0 ? retryHeader : undefined)
  return new ApiError(response.status, code, body.detail ?? response.statusText, retryAfter)
}

async function call<T>(path: string, init: RequestInit = {}, turnstileToken?: string): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (turnstileToken) headers.set('X-Turnstile-Token', turnstileToken)
  let response: Response
  try {
    response = await fetch(`${API_URL}${path}`, { ...init, headers })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Network error')
  }
  if (!response.ok) throw await toApiError(response)
  return (await response.json()) as T
}

const sharePath = (code: string) => `/api/v1/shares/${encodeURIComponent(code)}`

export const api = {
  createShare: (body: CreateShareRequest) =>
    call<CreateShareResponse>('/api/v1/shares', { method: 'POST', body: JSON.stringify(body) }),

  complete: (code: string, turnstileToken?: string) =>
    call<CompleteShareResponse>(`${sharePath(code)}/complete`, { method: 'POST' }, turnstileToken),

  info: (code: string, turnstileToken?: string) => call<ShareInfo>(sharePath(code), {}, turnstileToken),

  download: (code: string, authToken?: string, turnstileToken?: string) =>
    call<DownloadResponse>(
      `${sharePath(code)}/download`,
      { method: 'POST', body: JSON.stringify(authToken ? { authToken } : {}) },
      turnstileToken,
    ),
}

/**
 * PUT the encrypted file straight to R2 with the presigned URL (the API never sees the bytes).
 * XMLHttpRequest instead of fetch because fetch cannot report upload progress.
 */
export function uploadToStorage(
  target: UploadTarget,
  body: Uint8Array<ArrayBuffer>,
  onProgress: (fraction: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', target.url)
    for (const [name, value] of Object.entries(target.headers)) xhr.setRequestHeader(name, value)
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total)
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new ApiError(xhr.status, 'UNKNOWN', 'Upload failed')))
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', 'Upload failed'))
    // Typeless Blob: no Content-Type header, which the presigned signature does not include
    xhr.send(new Blob([body]))
  })
}

export async function fetchEncryptedFile(url: string): Promise<Uint8Array<ArrayBuffer>> {
  let response: Response
  try {
    response = await fetch(url)
  } catch {
    throw new ApiError(0, 'NETWORK', 'Download failed')
  }
  if (!response.ok) throw new ApiError(response.status, 'UNKNOWN', 'Download failed')
  return new Uint8Array(await response.arrayBuffer())
}
