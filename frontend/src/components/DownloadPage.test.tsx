// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n/I18nProvider'
import { toBase64 } from '../lib/base64'
import { deriveFromPassword, encryptBytes, encryptMeta, generateFileKey, randomSalt } from '../lib/crypto'
import { DownloadPage } from './DownloadPage'

const R2_URL = 'https://r2.example/get'
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let saved: Blob | undefined

beforeEach(() => {
  saved = undefined
  URL.createObjectURL = vi.fn((blob: Blob) => {
    saved = blob
    return 'blob:test'
  })
  URL.revokeObjectURL = vi.fn()
  HTMLAnchorElement.prototype.click = vi.fn()
})

afterEach(() => vi.unstubAllGlobals())

function renderPage(initialCode?: string) {
  render(
    <I18nProvider lang="en">
      <DownloadPage initialCode={initialCode} />
    </I18nProvider>,
  )
  return userEvent.setup()
}

describe('DownloadPage', () => {
  it('downloads and decrypts an unprotected file end to end', async () => {
    const { key, fileKey } = await generateFileKey()
    const encrypted = await encryptBytes(key, new TextEncoder().encode('hello dropcode'))
    const encMeta = await encryptMeta(key, { name: 'note.txt', type: 'text/plain' })

    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/api/v1/shares/ABC23')) {
        return json(200, { protected: false, size: encrypted.length, expiresAt: '2030-01-01T00:00:00Z' })
      }
      if (url.endsWith('/api/v1/shares/ABC23/download') && init?.method === 'POST') {
        return json(200, { url: R2_URL, encMeta, fileKey, downloadsLeft: 0 })
      }
      if (url === R2_URL) return new Response(encrypted)
      return json(404, { code: 'SHARE_NOT_FOUND' })
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = renderPage()
    await user.type(screen.getAllByRole('textbox')[0], 'abc23')
    await user.click(await screen.findByRole('button', { name: 'Download' }))

    expect(await screen.findByText(/Downloaded/)).toHaveTextContent('note.txt')
    expect(await readText(saved!)).toBe('hello dropcode')
  })

  it('looks up a deep-linked code without any typing', async () => {
    const fetchMock = vi.fn(async () => json(200, { protected: false, size: 5, expiresAt: '2030-01-01T00:00:00Z' }))
    vi.stubGlobal('fetch', fetchMock)

    renderPage('ABC23')

    expect(await screen.findByRole('button', { name: 'Download' })).toBeEnabled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows a generic message for unknown codes', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(404, { code: 'SHARE_NOT_FOUND' })))

    const user = renderPage()
    await user.type(screen.getAllByRole('textbox')[0], 'ZZZZZ')

    expect(await screen.findByRole('alert')).toHaveTextContent('No file found for this code')
  })

  it('keeps the file open after a wrong password so the user can retry', async () => {
    const salt = randomSalt()
    const right = await deriveFromPassword('right', salt)
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (url.endsWith('/download')) {
          const sent = JSON.parse(String(init?.body)).authToken
          return sent === right.authToken
            ? json(500, {})
            : json(401, { code: 'WRONG_PASSWORD' })
        }
        return json(200, { protected: true, size: 10, expiresAt: '2030-01-01T00:00:00Z', salt: toBase64(salt) })
      }),
    )

    const user = renderPage()
    await user.type(screen.getAllByRole('textbox')[0], 'ABC23')
    await user.type(await screen.findByLabelText('Password'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Download' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong password.')
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })
})

function readText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}
