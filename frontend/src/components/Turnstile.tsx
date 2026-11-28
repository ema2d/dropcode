import { useEffect, useRef } from 'react'
import { useI18n } from '../i18n/I18nProvider'

declare global {
  interface Window {
    turnstile?: {
      render: (el: HTMLElement, options: Record<string, unknown>) => string
      remove: (widgetId: string) => void
    }
  }
}

// Default: Cloudflare's public test key (always passes). Set VITE_TURNSTILE_SITE_KEY in production.
const SITE_KEY: string = import.meta.env.VITE_TURNSTILE_SITE_KEY ?? '1x00000000000000000000AA'
const SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

let scriptLoading: Promise<void> | null = null

function loadScript(): Promise<void> {
  if (window.turnstile) return Promise.resolve()
  scriptLoading ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      scriptLoading = null
      reject(new Error('Turnstile failed to load'))
    }
    document.head.appendChild(script)
  })
  return scriptLoading
}

/** Cloudflare Turnstile challenge. Only shown after the API answers CAPTCHA_REQUIRED. */
export function Turnstile({ onToken }: { onToken: (token: string) => void }) {
  const container = useRef<HTMLDivElement>(null)
  const { t, lang } = useI18n()

  useEffect(() => {
    let widgetId: string | undefined
    let cancelled = false
    loadScript()
      .then(() => {
        if (cancelled || !container.current || !window.turnstile) return
        widgetId = window.turnstile.render(container.current, { sitekey: SITE_KEY, callback: onToken, language: lang })
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
      if (widgetId) window.turnstile?.remove(widgetId)
    }
  }, [onToken, lang])

  return (
    <div className="mt-4 flex flex-col items-center gap-2" role="group" aria-label={t('verifyHuman')}>
      <p className="text-sm text-amber-300">{t('verifyHuman')}</p>
      <div ref={container} />
    </div>
  )
}
