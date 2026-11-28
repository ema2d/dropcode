import { useEffect, useRef, useState, type ClipboardEvent, type KeyboardEvent } from 'react'
import { useI18n } from '../i18n/I18nProvider'
import { CODE_LENGTH, cleanCodeInput } from '../lib/share-code'

interface Props {
  initialCode?: string
  disabled?: boolean
  onComplete: (code: string) => void
}

/** Five boxes; typing moves to the next box, Backspace goes back, pasting fills all. */
export function CodeInput({ initialCode = '', disabled, onComplete }: Props) {
  const { t } = useI18n()
  const [chars, setChars] = useState<string[]>(() => toChars(initialCode))
  const inputs = useRef<Array<HTMLInputElement | null>>([])

  useEffect(() => {
    if (!initialCode) inputs.current[0]?.focus()
  }, [initialCode])

  const update = (next: string[], focusIndex: number) => {
    setChars(next)
    inputs.current[Math.min(focusIndex, CODE_LENGTH - 1)]?.focus()
    const code = next.join('')
    if (code.length === CODE_LENGTH) onComplete(code)
  }

  const handleChange = (index: number, value: string) => {
    const cleaned = cleanCodeInput(value)
    if (!cleaned) return
    if (cleaned.length > 1) {
      // Autofill or fast typing: spread the characters from this box onwards
      const next = [...chars]
      ;[...cleaned].slice(0, CODE_LENGTH - index).forEach((c, i) => (next[index + i] = c))
      update(next, index + cleaned.length)
      return
    }
    const next = [...chars]
    next[index] = cleaned
    update(next, index + 1)
  }

  const handleKeyDown = (index: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Backspace') return
    e.preventDefault()
    const next = [...chars]
    if (next[index]) {
      next[index] = ''
      setChars(next)
    } else if (index > 0) {
      next[index - 1] = ''
      setChars(next)
      inputs.current[index - 1]?.focus()
    }
  }

  const handlePaste = (e: ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault()
    const pasted = cleanCodeInput(e.clipboardData.getData('text')).slice(0, CODE_LENGTH)
    if (pasted) update(toChars(pasted), pasted.length)
  }

  return (
    // Codes are Latin: keep left-to-right even in the Arabic layout
    <div dir="ltr" className="flex justify-center gap-2 sm:gap-3">
      {chars.map((char, i) => (
        <input
          key={i}
          ref={(el) => {
            inputs.current[i] = el
          }}
          value={char}
          disabled={disabled}
          inputMode="text"
          autoCapitalize="characters"
          autoComplete="off"
          autoCorrect="off"
          spellCheck={false}
          maxLength={CODE_LENGTH}
          aria-label={`${t('codeDigit')} ${i + 1}`}
          onChange={(e) => handleChange(i, e.target.value)}
          onKeyDown={(e) => handleKeyDown(i, e)}
          onPaste={handlePaste}
          onFocus={(e) => e.target.select()}
          className="h-16 w-12 rounded-xl border-2 border-slate-700 bg-slate-900 text-center font-mono text-3xl font-bold uppercase text-sky-300 outline-none transition focus:border-sky-400 disabled:opacity-50 sm:h-20 sm:w-16"
        />
      ))}
    </div>
  )
}

function toChars(code: string): string[] {
  const cleaned = cleanCodeInput(code).slice(0, CODE_LENGTH)
  return Array.from({ length: CODE_LENGTH }, (_, i) => cleaned[i] ?? '')
}
