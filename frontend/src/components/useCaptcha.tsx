import { useCallback, useState } from 'react'
import { ApiError } from '../lib/api'
import { Turnstile } from './Turnstile'

/**
 * Runs an API call; if the backend answers CAPTCHA_REQUIRED, shows Turnstile
 * and retries the same call with the token once the user has solved it.
 */
export function useCaptcha() {
  const [resolveToken, setResolveToken] = useState<((token: string) => void) | null>(null)

  const withCaptcha = useCallback(async <T,>(call: (turnstileToken?: string) => Promise<T>): Promise<T> => {
    try {
      return await call()
    } catch (error) {
      if (!(error instanceof ApiError && error.code === 'CAPTCHA_REQUIRED')) throw error
      const token = await new Promise<string>((resolve) => setResolveToken(() => resolve))
      setResolveToken(null)
      return call(token)
    }
  }, [])

  const captchaElement = resolveToken ? <Turnstile onToken={resolveToken} /> : null
  return { withCaptcha, captchaElement }
}
