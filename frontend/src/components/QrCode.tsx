import { useEffect, useState } from 'react'
import QRCode from 'qrcode'

export function QrCode({ value, alt }: { value: string; alt: string }) {
  const [src, setSrc] = useState<string>()

  useEffect(() => {
    let active = true
    QRCode.toDataURL(value, { margin: 1, width: 200, color: { dark: '#0f172a', light: '#ffffff' } })
      .then((url) => active && setSrc(url))
      .catch(() => undefined)
    return () => {
      active = false
    }
  }, [value])

  return src ? <img src={src} alt={alt} width={160} height={160} className="rounded-lg" /> : null
}
