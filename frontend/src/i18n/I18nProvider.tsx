import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError } from '../lib/api'
import { dictionaries, errorMessages, type Key, type Lang } from './strings'

interface I18n {
  lang: Lang
  locale: string
  t: (key: Key) => string
  errorText: (error: unknown) => string
  toggleLang: () => void
}

const I18nContext = createContext<I18n | null>(null)

function initialLang(): Lang {
  const saved = localStorage.getItem('lang')
  if (saved === 'ar' || saved === 'en') return saved
  return navigator.language?.toLowerCase().startsWith('ar') ? 'ar' : 'en'
}

export function I18nProvider({ children, lang: forced }: { children: ReactNode; lang?: Lang }) {
  const [lang, setLang] = useState<Lang>(forced ?? initialLang)

  useEffect(() => {
    // RTL for Arabic: flips the whole layout
    document.documentElement.lang = lang
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
    localStorage.setItem('lang', lang)
  }, [lang])

  const t = useCallback((key: Key) => dictionaries[lang][key], [lang])

  const errorText = useCallback(
    (error: unknown) => {
      const code = error instanceof ApiError ? error.code : 'UNKNOWN'
      const seconds = error instanceof ApiError ? error.retryAfterSeconds ?? 60 : 60
      return errorMessages[lang][code].replace('{s}', String(seconds))
    },
    [lang],
  )

  const value = useMemo<I18n>(
    () => ({
      lang,
      locale: lang === 'ar' ? 'ar-SA' : 'en',
      t,
      errorText,
      toggleLang: () => setLang((l) => (l === 'ar' ? 'en' : 'ar')),
    }),
    [lang, t, errorText],
  )

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useI18n(): I18n {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n must be used inside I18nProvider')
  return ctx
}
