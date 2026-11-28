import { readFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'
import { FakeBackend } from './fake-backend'

let backend: FakeBackend

test.beforeEach(async ({ page }) => {
  backend = new FakeBackend()
  await backend.install(page)
})

const TEXT = 'Hello from Dropcode — مرحبا 👋'

async function sendFile(page: Page, options: { password?: string; downloads?: string } = {}) {
  await page.goto('/')
  await page.getByTestId('file-input').setInputFiles({
    name: 'hello.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from(TEXT),
  })
  if (options.password || options.downloads) {
    await page.getByText('More options').click()
    if (options.downloads) await page.getByLabel('Downloads allowed').selectOption(options.downloads)
    if (options.password) await page.getByLabel('Password (optional)').fill(options.password)
  }
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  const code = (await page.getByTestId('share-code').textContent())!.trim()
  expect(code).toMatch(/^[23456789A-HJKMNP-Z]{5}$/)
  return code
}

async function downloadFrom(page: Page, code: string, password?: string) {
  await page.goto(`/${code}`)
  if (password) await page.getByLabel('Password').fill(password)
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Download', exact: true }).click(),
  ])
  return { name: download.suggestedFilename(), text: (await readFile((await download.path())!)).toString() }
}

test('sends and receives a file; only ciphertext reaches storage', async ({ page }) => {
  const code = await sendFile(page)

  // Storage got encrypted bytes, never the plain text or the file name
  const stored = backend.objects.get(code)!
  expect(stored.toString()).not.toContain('Hello')
  expect(backend.shares.get(code)!.encMeta).not.toContain('hello')

  const file = await downloadFrom(page, code)
  expect(file).toEqual({ name: 'hello.txt', text: TEXT })

  // One download allowed: the code is gone now
  await page.goto(`/${code}`)
  await expect(page.getByRole('alert')).toHaveText('No file found for this code, or it has expired.')
})

test('password-protected file: the key never leaves the browser', async ({ page }) => {
  const code = await sendFile(page, { password: 'correct horse', downloads: '3' })

  const share = backend.shares.get(code)!
  expect(share.protected).toBe(true)
  expect(share.fileKey).toBeUndefined()
  expect(share.authToken).toBeTruthy()

  await page.goto(`/${code}`)
  await expect(page.getByText('This file is password-protected.')).toBeVisible()
  await page.getByLabel('Password').fill('wrong')
  await page.getByRole('button', { name: 'Download', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('Wrong password.')

  const file = await downloadFrom(page, code, 'correct horse')
  expect(file.text).toBe(TEXT)
  expect(backend.shares.get(code)!.remaining).toBe(2)
})

test('typing a code in the Receive tab', async ({ page }) => {
  const code = await sendFile(page)
  await page.goto('/')
  await page.getByRole('tab', { name: 'Receive' }).click()
  await page.getByLabel('Character 1').pressSequentially(code.toLowerCase())
  await expect(page.getByText('A file is waiting for you')).toBeVisible()
})

test('shows the human check when the API asks for it, then retries', async ({ page }) => {
  const code = await sendFile(page)
  backend.captchaRequired = true

  await page.goto(`/${code}`)
  await expect(page.getByText('Please confirm you are human to continue.')).toBeVisible()
  await page.getByRole('button', { name: 'I am human' }).click()

  await expect(page.getByText('A file is waiting for you')).toBeVisible()
  expect(backend.turnstileTokens).toEqual(['fake-turnstile-token'])
})

test('rejects files over 30 MB before uploading anything', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('file-input').setInputFiles({
    name: 'big.bin',
    mimeType: 'application/octet-stream',
    buffer: Buffer.alloc(30 * 1024 * 1024),
  })
  await expect(page.getByRole('alert')).toHaveText('The file is larger than 30 MB.')
  await expect(page.getByRole('button', { name: 'Upload', exact: true })).toBeDisabled()
  expect(backend.shares.size).toBe(0)
})

test.describe('Arabic', () => {
  test.use({ locale: 'ar-SA' })

  test('uses a right-to-left layout and can switch to English', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
    await expect(page.getByRole('tab', { name: 'إرسال' })).toBeVisible()

    await page.getByRole('button', { name: 'English' }).click()
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')
    await expect(page.getByRole('tab', { name: 'Send' })).toBeVisible()
  })
})
