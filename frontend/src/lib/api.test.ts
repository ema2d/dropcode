import { describe, expect, it } from 'vitest'
import { toApiError } from './api'

const problem = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/problem+json', ...headers } })

describe('toApiError', () => {
  it('reads the stable error code from ProblemDetail', async () => {
    const error = await toApiError(problem(404, { code: 'SHARE_NOT_FOUND', detail: 'No share' }))
    expect(error.status).toBe(404)
    expect(error.code).toBe('SHARE_NOT_FOUND')
  })

  it('reads retryAfterSeconds for rate limits', async () => {
    const error = await toApiError(problem(429, { code: 'RATE_LIMITED', retryAfterSeconds: 42 }))
    expect(error.retryAfterSeconds).toBe(42)
  })

  it('falls back to the Retry-After header', async () => {
    const error = await toApiError(problem(429, { code: 'RATE_LIMITED' }, { 'Retry-After': '7' }))
    expect(error.retryAfterSeconds).toBe(7)
  })

  it('handles non-JSON and unknown codes', async () => {
    expect((await toApiError(new Response('<html>', { status: 502 }))).code).toBe('UNKNOWN')
    expect((await toApiError(problem(400, { title: 'Bad Request' }))).code).toBe('INVALID_REQUEST')
    expect((await toApiError(problem(500, { code: 'SOMETHING_NEW' }))).code).toBe('UNKNOWN')
  })
})
