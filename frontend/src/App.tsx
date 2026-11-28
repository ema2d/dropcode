import { useEffect, useState } from 'react'
import { DownloadPage } from './components/DownloadPage'
import { UploadPage } from './components/UploadPage'
import { useI18n } from './i18n/I18nProvider'
import { codeFromPath } from './lib/share-code'
import { takeSharedFile } from './lib/shared-file'

type Tab = 'send' | 'receive'

export function App() {
  const { t, toggleLang } = useI18n()
  // Deep link: dropcode.app/ABC23 opens "Receive" with the code filled in
  const [deepLinkCode] = useState(() => codeFromPath(window.location.pathname) ?? undefined)
  const [tab, setTab] = useState<Tab>(deepLinkCode ? 'receive' : 'send')
  const [sharedFile, setSharedFile] = useState<File | null>(null)

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    if (!params.has('shared')) return
    window.history.replaceState(null, '', '/')
    takeSharedFile().then((file) => {
      if (file) {
        setTab('send')
        setSharedFile(file)
      }
    })
  }, [])

  return (
    <div className="mx-auto flex min-h-dvh max-w-lg flex-col px-4 py-6">
      <header className="mb-6 flex items-center justify-between">
        <h1 className="flex items-center gap-2 text-2xl font-bold">
          <img src="/icon.svg" alt="" width={32} height={32} />
          Dropcode
        </h1>
        <button type="button" onClick={toggleLang} className="rounded-lg px-3 py-1 text-sm text-slate-300 hover:bg-slate-800">
          {t('language')}
        </button>
      </header>
      <p className="mb-6 text-slate-400">{t('appTagline')}</p>

      <nav className="mb-6 grid grid-cols-2 rounded-xl bg-slate-900 p-1" role="tablist">
        {(['send', 'receive'] as const).map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            onClick={() => setTab(id)}
            className={`rounded-lg py-2 font-medium transition ${tab === id ? 'bg-slate-700' : 'text-slate-400'}`}
          >
            {id === 'send' ? t('tabSend') : t('tabReceive')}
          </button>
        ))}
      </nav>

      <main className="flex-1">
        {tab === 'send' ? <UploadPage sharedFile={sharedFile} /> : <DownloadPage initialCode={deepLinkCode} />}
      </main>
    </div>
  )
}
