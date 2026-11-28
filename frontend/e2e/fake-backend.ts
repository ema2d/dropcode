import type { Page, Route } from '@playwright/test'

export const API = 'http://api.test'
const R2 = 'https://r2.test'
const CODES = ['ABC23', 'XYZ45', 'K7M2Q', 'HJ8PW']

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT',
  'Access-Control-Allow-Headers': 'Content-Type, X-Turnstile-Token',
}

/** Stands in for the Turnstile script: renders a button that "solves" the challenge. */
const FAKE_TURNSTILE = `
window.turnstile = {
  render(el, options) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = 'I am human';
    b.onclick = () => options.callback('fake-turnstile-token');
    el.appendChild(b);
    return 'widget-1';
  },
  remove() {},
};`

interface StoredShare {
  code: string
  size: number
  protected: boolean
  encMeta: string
  authToken?: string
  salt?: string
  fileKey?: string
  remaining: number
  ready: boolean
}

/** In-memory fake of the Dropcode API (same JSON contract), R2 and Turnstile. */
export class FakeBackend {
  readonly shares = new Map<string, StoredShare>()
  /** What R2 received: the ciphertext the browser uploaded. */
  readonly objects = new Map<string, Buffer>()
  readonly turnstileTokens: string[] = []
  captchaRequired = false
  private next = 0

  async install(page: Page) {
    await page.route(`${API}/**`, (route) => this.handleApi(route))
    await page.route(`${R2}/**`, (route) => this.handleR2(route))
    await page.route('https://challenges.cloudflare.com/**', (route) =>
      route.fulfill({ contentType: 'text/javascript', body: FAKE_TURNSTILE }),
    )
  }

  private async handleApi(route: Route) {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS })
    const path = new URL(request.url()).pathname

    if (request.method() === 'POST' && path === '/api/v1/shares') {
      const body = request.postDataJSON()
      const code = CODES[this.next++ % CODES.length]
      this.shares.set(code, {
        code,
        size: body.size,
        protected: body.protected,
        encMeta: body.encMeta,
        authToken: body.authToken,
        salt: body.salt,
        fileKey: body.fileKey,
        remaining: body.downloads ?? 1,
        ready: false,
      })
      return json(route, 201, {
        code,
        upload: { url: `${R2}/objects/${code}`, headers: {}, expiresAt: new Date(Date.now() + 600_000).toISOString() },
      })
    }

    const match = path.match(/^\/api\/v1\/shares\/([^/]+)(\/complete|\/download)?$/)
    if (!match) return json(route, 404, { code: 'SHARE_NOT_FOUND' })
    const [, code, action] = match
    const share = this.shares.get(code)

    if (this.captchaRequired) {
      const token = request.headers()['x-turnstile-token']
      if (!token) return json(route, 403, { code: 'CAPTCHA_REQUIRED' })
      this.turnstileTokens.push(token)
      this.captchaRequired = false
    }

    const expiresAt = new Date(Date.now() + 86_400_000).toISOString()

    if (action === '/complete') {
      if (!share) return json(route, 404, { code: 'SHARE_NOT_FOUND' })
      if (this.objects.get(code)?.length !== share.size) return json(route, 409, { code: 'UPLOAD_NOT_FINISHED' })
      share.ready = true
      return json(route, 200, { code, expiresAt, downloadsLeft: share.remaining })
    }

    if (!share?.ready) return json(route, 404, { code: 'SHARE_NOT_FOUND' })

    if (action === '/download') {
      const authToken = request.postDataJSON()?.authToken
      if (share.protected && authToken !== share.authToken) return json(route, 401, { code: 'WRONG_PASSWORD' })
      share.remaining--
      if (share.remaining === 0) this.shares.delete(code)
      return json(route, 200, {
        url: `${R2}/objects/${code}`,
        encMeta: share.encMeta,
        fileKey: share.fileKey,
        downloadsLeft: share.remaining,
      })
    }

    return json(route, 200, { protected: share.protected, size: share.size, expiresAt, salt: share.salt })
  }

  private async handleR2(route: Route) {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS })
    const code = new URL(request.url()).pathname.split('/').pop()!
    if (request.method() === 'PUT') {
      this.objects.set(code, request.postDataBuffer() ?? Buffer.alloc(0))
      return route.fulfill({ status: 200, headers: CORS })
    }
    const object = this.objects.get(code)
    return object
      ? route.fulfill({ status: 200, headers: { ...CORS, 'Content-Type': 'application/octet-stream' }, body: object })
      : route.fulfill({ status: 404, headers: CORS })
  }
}

function json(route: Route, status: number, body: unknown) {
  return route.fulfill({
    status,
    headers: { ...CORS, 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
    body: JSON.stringify(body),
  })
}
