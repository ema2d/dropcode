import { useEffect, useRef, useState } from 'react'
import { useI18n } from '../i18n/I18nProvider'
import { ApiError, api, fetchEncryptedFile, type ShareInfo } from '../lib/api'
import { fromBase64 } from '../lib/base64'
import { decryptBytes, decryptMeta, deriveFromPassword, importFileKey } from '../lib/crypto'
import { formatBytes, formatDateTime, saveBlob } from '../lib/format'
import { CodeInput } from './CodeInput'
import { useCaptcha } from './useCaptcha'

type Phase =
  | { kind: 'enter' }
  | { kind: 'lookup' }
  | { kind: 'found'; code: string; info: ShareInfo }
  | { kind: 'downloading'; code: string; info: ShareInfo; step: 'downloading' | 'decrypting' }
  | { kind: 'done'; name: string }

export function DownloadPage({ initialCode }: { initialCode?: string }) {
  const { t, locale, errorText } = useI18n()
  const { withCaptcha, captchaElement } = useCaptcha()
  const [phase, setPhase] = useState<Phase>({ kind: 'enter' })
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [inputKey, setInputKey] = useState(0)
  const autoLookedUp = useRef(false)

  // Deep link (/ABC23): look the code up right away instead of waiting for input
  useEffect(() => {
    if (initialCode && !autoLookedUp.current) {
      autoLookedUp.current = true
      void lookUp(initialCode)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialCode])

  async function lookUp(code: string) {
    setError(undefined)
    setPhase({ kind: 'lookup' })
    try {
      const info = await withCaptcha((token) => api.info(code, token))
      setPhase({ kind: 'found', code, info })
    } catch (e) {
      setError(errorText(e))
      setPhase({ kind: 'enter' })
    }
  }

  async function download() {
    if (phase.kind !== 'found') return
    const { code, info } = phase
    setError(undefined)
    setPhase({ kind: 'downloading', code, info, step: 'downloading' })
    try {
      let key: CryptoKey | undefined
      let authToken: string | undefined
      if (info.protected) {
        const derived = await deriveFromPassword(password, fromBase64(info.salt ?? ''))
        key = derived.key
        authToken = derived.authToken
      }
      const grant = await withCaptcha((token) => api.download(code, authToken, token))
      key ??= await importFileKey(grant.fileKey ?? '')
      const encrypted = await fetchEncryptedFile(grant.url ?? '')

      setPhase({ kind: 'downloading', code, info, step: 'decrypting' })
      let plain: ArrayBuffer
      let meta
      try {
        plain = await decryptBytes(key, encrypted)
        meta = await decryptMeta(key, grant.encMeta ?? '')
      } catch {
        throw new Error('decrypt')
      }
      saveBlob(plain, meta.name, meta.type)
      setPhase({ kind: 'done', name: meta.name })
    } catch (e) {
      setError(e instanceof ApiError ? errorText(e) : t('decryptFailed'))
      // Wrong password: stay on the file so the user can try again
      setPhase(
        e instanceof ApiError && e.code === 'WRONG_PASSWORD' ? { kind: 'found', code, info } : { kind: 'enter' },
      )
    }
  }

  function reset() {
    setPassword('')
    setError(undefined)
    setInputKey((k) => k + 1)
    setPhase({ kind: 'enter' })
  }

  return (
    <section className="space-y-5">
      {(phase.kind === 'enter' || phase.kind === 'lookup') && (
        <>
          <h2 className="text-center text-lg font-medium">{t('enterCode')}</h2>
          <CodeInput
            key={inputKey}
            initialCode={initialCode}
            disabled={phase.kind === 'lookup'}
            onComplete={lookUp}
          />
          {phase.kind === 'lookup' && <p className="text-center text-sm text-slate-400">{t('lookingUp')}</p>}
        </>
      )}

      {(phase.kind === 'found' || phase.kind === 'downloading') && (
        <div className="space-y-4 rounded-2xl bg-slate-900 p-5">
          <p className="text-lg font-medium">{t('fileReady')}</p>
          <dl className="grid grid-cols-2 gap-y-1 text-sm">
            <dt className="text-slate-400">{t('size')}</dt>
            <dd>{formatBytes(phase.info.size ?? 0, locale)}</dd>
            <dt className="text-slate-400">{t('expiresAt')}</dt>
            <dd>{phase.info.expiresAt ? formatDateTime(phase.info.expiresAt, locale) : ''}</dd>
          </dl>
          {phase.info.protected && (
            <label className="flex flex-col gap-1 text-sm">
              <span>{t('protectedFile')}</span>
              <input
                type="password"
                value={password}
                autoComplete="off"
                placeholder={t('enterPassword')}
                aria-label={t('enterPassword')}
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && password && download()}
                className="rounded-lg bg-slate-800 p-2"
              />
            </label>
          )}
          <p className="text-xs text-slate-400">{t('usesDownload')}</p>
          <button
            type="button"
            onClick={download}
            disabled={phase.kind === 'downloading' || (phase.info.protected && !password)}
            className="w-full rounded-xl bg-sky-500 py-3 text-lg font-semibold text-slate-950 hover:bg-sky-400 disabled:opacity-40"
          >
            {phase.kind === 'downloading' ? t(phase.step) : t('download')}
          </button>
        </div>
      )}

      {phase.kind === 'done' && (
        <p className="rounded-xl bg-emerald-950 p-4 text-center text-emerald-200">
          {t('downloaded')}: <span dir="auto">{phase.name}</span>
        </p>
      )}

      {captchaElement}
      {error && <p role="alert" className="rounded-lg bg-red-950 p-3 text-sm text-red-200">{error}</p>}

      {phase.kind !== 'enter' && phase.kind !== 'lookup' && (
        <button type="button" onClick={reset} className="block w-full text-center text-sky-300 underline">
          {t('receiveAnother')}
        </button>
      )}
    </section>
  )
}
