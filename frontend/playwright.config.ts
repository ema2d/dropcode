import { defineConfig, devices } from '@playwright/test'

/**
 * Browser E2E tests: the real built app in real Chromium, with the API, R2 and Turnstile
 * replaced by an in-memory fake (e2e/fake-backend.ts). Encryption, uploads with progress,
 * downloads and decryption all run for real; the backend has its own tests.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    locale: 'en-US',
    // page.route() cannot see requests that go through a service worker
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'npx vite build && npx vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    env: { VITE_API_URL: 'http://api.test' },
    timeout: 120_000,
  },
})
