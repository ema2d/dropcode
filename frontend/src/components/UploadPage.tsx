import { useEffect, useRef, useState, type DragEvent } from 'react'
import { useI18n } from '../i18n/I18nProvider'
import type { Key } from '../i18n/strings'
import { api, uploadToStorage, type CompleteShareResponse } from '../lib/api'
import { toBase64 } from '../lib/base64'
import {
  ENCRYPTION_OVERHEAD,
  deriveFromPassword,
  encryptBytes,
  encryptMeta,
  generateFileKey,
  randomSalt,
} from '../lib/crypto'
import { MAX_FILE_BYTES, formatBytes, formatDateTime } from '../lib/format'
import { QrCode } from './QrCode'
import { useCaptcha } from './useCaptcha'

const DOWNLOAD_OPTIONS = [1, 3, 5, 10] as const
const EXPIRY_OPTIONS: ReadonlyArray<{ value: string; label: Key }> = [
  { value: '10m', label: 'expiry10m' },
  { value: '1h', label: 'expiry1h' },
  { value: '24h', label: 'expiry24h' },
  { value: '7d', label: 'expiry7d' },
]

type Phase =
  | { kind: 'pick' }
  | { kind: 'working'; step: 'encrypting' | 'uploading' | 'finishing'; progress: number }
  | { kind: 'done'; result: CompleteShareResponse }

export function UploadPage({ sharedFile }: { sharedFile: File | null }) {
  const { t, locale, errorText } = useI18n()
  const { withCaptcha, captchaElement } = useCaptcha()
  const [file, setFile] = useState<File | null>(null)
  const [downloads, setDownloads] = useState<number>(1)
  const [expiry, setExpiry] = useState('24h')
  const [password, setPassword] = useState('')
  const [phase, setPhase] = useState<Phase>({ kind: 'pick' })
  const [error, setError] = useState<string>()
  const [dragging, setDragging] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (sharedFile) choose(sharedFile)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sharedFile])

  function choose(selected: File | undefined | null) {
    setError(undefined)
    if (!selected) return
    if (selected.size === 0) return setError(t('emptyFile'))
    if (selected.size + ENCRYPTION_OVERHEAD > MAX_FILE_BYTES) return setError(t('fileTooBig'))
    setFile(selected)
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    choose(e.dataTransfer.files[0])
  }

  async function send() {
    if (!file) return
    setError(undefined)
    try {
      setPhase({ kind: 'working', step: 'encrypting', progress: 0 })
      const isProtected = password.length > 0
      let key: CryptoKey
      let keyFields: { authToken?: string; salt?: string; fileKey?: string }
      if (isProtected) {
        const salt = randomSalt()
        const derived = await deriveFromPassword(password, salt)
        key = derived.key
        keyFields = { authToken: derived.authToken, salt: toBase64(salt) }
      } else {
        const generated = await generateFileKey()
        key = generated.key
        keyFields = { fileKey: generated.fileKey }
      }
      const encrypted = await encryptBytes(key, await file.arrayBuffer())
      const encMeta = await encryptMeta(key, { name: file.name, type: file.type })

      const created = await api.createShare({
        size: encrypted.length,
        downloads,
        expiry,
        protected: isProtected,
        encMeta,
        ...keyFields,
      })

      setPhase({ kind: 'working', step: 'uploading', progress: 0 })
      await uploadToStorage(created.upload, encrypted, (progress) =>
        setPhase({ kind: 'working', step: 'uploading', progress }),
      )

      setPhase({ kind: 'working', step: 'finishing', progress: 1 })
      const result = await withCaptcha((token) => api.complete(created.code, token))
      setPhase({ kind: 'done', result })
    } catch (e) {
      setError(errorText(e))
      setPhase({ kind: 'pick' })
    }
  }

  function reset() {
    setFile(null)
    setPassword('')
    setError(undefined)
    setPhase({ kind: 'pick' })
  }

  if (phase.kind === 'done') return <ShareResult result={phase.result} onReset={reset} />

  const working = phase.kind === 'working'

  return (
    <section className="space-y-5">
      <button
        type="button"
        onClick={() => fileInput.current?.click()}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        disabled={working}
        className={`flex w-full flex-col items-center gap-2 rounded-2xl border-2 border-dashed p-8 text-center transition ${
          dragging ? 'border-sky-400 bg-sky-950/40' : 'border-slate-700 hover:border-slate-500'
        }`}
      >
        {file ? (
          <>
            <span className="max-w-full truncate font-medium" dir="auto">{file.name}</span>
            <span className="text-sm text-slate-400">{formatBytes(file.size, locale)} · {t('change')}</span>
          </>
        ) : (
          <>
            <span className="text-4xl" aria-hidden>⬆</span>
            <span className="font-medium">{t('dropTitle')}</span>
            <span className="text-sm text-slate-400">{t('dropHint')}</span>
          </>
        )}
      </button>
      <input
        ref={fileInput}
        type="file"
        className="hidden"
        data-testid="file-input"
        onChange={(e) => choose(e.target.files?.[0])}
      />

      <details className="rounded-xl bg-slate-900 p-4">
        <summary className="cursor-pointer select-none font-medium">{t('advanced')}</summary>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            {t('downloads')}
            <select
              value={downloads}
              onChange={(e) => setDownloads(Number(e.target.value))}
              className="rounded-lg bg-slate-800 p-2"
            >
              {DOWNLOAD_OPTIONS.map((n) => (
                <option key={n} value={n}>{new Intl.NumberFormat(locale).format(n)}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            {t('expiry')}
            <select value={expiry} onChange={(e) => setExpiry(e.target.value)} className="rounded-lg bg-slate-800 p-2">
              {EXPIRY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{t(o.label)}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm sm:col-span-2">
            {t('password')}
            <input
              type="password"
              value={password}
              autoComplete="new-password"
              onChange={(e) => setPassword(e.target.value)}
              className="rounded-lg bg-slate-800 p-2"
            />
            <span className="text-xs text-slate-400">{t('passwordHint')}</span>
          </label>
        </div>
      </details>

      {!password && <p className="text-xs text-slate-400">{t('noPasswordNote')}</p>}

      {working && (
        <div className="space-y-2" aria-live="polite">
          <p className="text-sm text-slate-300">{t(phase.step)}</p>
          <div className="h-2 overflow-hidden rounded-full bg-slate-800">
            <div className="h-full bg-sky-400 transition-all" style={{ width: `${Math.round(phase.progress * 100)}%` }} />
          </div>
        </div>
      )}

      {captchaElement}
      {error && <p role="alert" className="rounded-lg bg-red-950 p-3 text-sm text-red-200">{error}</p>}

      <button
        type="button"
        onClick={send}
        disabled={!file || working}
        className="w-full rounded-xl bg-sky-500 py-3 text-lg font-semibold text-slate-950 transition hover:bg-sky-400 disabled:opacity-40"
      >
        {t('send')}
      </button>
    </section>
  )
}

function ShareResult({ result, onReset }: { result: CompleteShareResponse; onReset: () => void }) {
  const { t, locale } = useI18n()
  const [copied, setCopied] = useState<'code' | 'link'>()
  const link = `${window.location.origin}/${result.code}`

  async function copy(text: string, what: 'code' | 'link') {
    await navigator.clipboard.writeText(text)
    setCopied(what)
    setTimeout(() => setCopied(undefined), 2000)
  }

  return (
    <section className="flex flex-col items-center gap-5 text-center">
      <p className="text-slate-400">{t('yourCode')}</p>
      <p dir="ltr" className="font-mono text-6xl font-bold tracking-[0.3em] text-sky-300" data-testid="share-code">
        {result.code}
      </p>
      <div className="flex gap-3">
        <button type="button" onClick={() => copy(result.code, 'code')} className="rounded-lg bg-slate-800 px-4 py-2">
          {copied === 'code' ? t('copied') : t('copy')}
        </button>
        <button type="button" onClick={() => copy(link, 'link')} className="rounded-lg bg-slate-800 px-4 py-2">
          {copied === 'link' ? t('copied') : t('copyLink')}
        </button>
      </div>
      <QrCode value={link} alt={t('qrAlt')} />
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm">
        <dt className="text-slate-400">{t('expiresAt')}</dt>
        <dd>{formatDateTime(result.expiresAt, locale)}</dd>
        <dt className="text-slate-400">{t('downloadsLeft')}</dt>
        <dd>{new Intl.NumberFormat(locale).format(result.downloadsLeft)}</dd>
      </dl>
      <button type="button" onClick={onReset} className="text-sky-300 underline">{t('sendAnother')}</button>
    </section>
  )
}
