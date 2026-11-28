import { describe, expect, it } from 'vitest'
import { fromBase64, toBase64 } from './base64'
import {
  ENCRYPTION_OVERHEAD,
  decryptBytes,
  decryptMeta,
  deriveFromPassword,
  encryptBytes,
  encryptMeta,
  generateFileKey,
  importFileKey,
  randomSalt,
  sanitizeFileName,
} from './crypto'

// Fewer PBKDF2 iterations keep the tests fast; the real value is used in one test below
const FAST = 1_000
const bytes = (text: string) => new TextEncoder().encode(text)

describe('password-based keys', () => {
  it('same password + salt gives the same authToken (so the server can verify it)', async () => {
    const salt = randomSalt()
    const a = await deriveFromPassword('correct horse', salt, FAST)
    const b = await deriveFromPassword('correct horse', salt, FAST)
    expect(a.authToken).toBe(b.authToken)
    expect(fromBase64(a.authToken)).toHaveLength(32)
  })

  it('different password or salt gives a different authToken', async () => {
    const salt = randomSalt()
    const base = await deriveFromPassword('pw', salt, FAST)
    expect((await deriveFromPassword('pw2', salt, FAST)).authToken).not.toBe(base.authToken)
    expect((await deriveFromPassword('pw', randomSalt(), FAST)).authToken).not.toBe(base.authToken)
  })

  it('a file encrypted by the uploader decrypts on the downloader side with the same password', async () => {
    const salt = randomSalt()
    const uploader = await deriveFromPassword('secret', salt)
    const encrypted = await encryptBytes(uploader.key, bytes('hello dropcode'))

    // The downloader only gets the salt (Base64) from the API
    const downloader = await deriveFromPassword('secret', fromBase64(toBase64(salt)))
    const plain = await decryptBytes(downloader.key, encrypted)
    expect(new TextDecoder().decode(plain)).toBe('hello dropcode')
  })

  it('the wrong password cannot decrypt', async () => {
    const salt = randomSalt()
    const right = await deriveFromPassword('right', salt, FAST)
    const wrong = await deriveFromPassword('wrong', salt, FAST)
    const encrypted = await encryptBytes(right.key, bytes('data'))
    await expect(decryptBytes(wrong.key, encrypted)).rejects.toThrow()
  })
})

describe('random file keys (unprotected shares)', () => {
  it('round-trips through the Base64 fileKey stored on the server', async () => {
    const { key, fileKey } = await generateFileKey()
    const encrypted = await encryptBytes(key, bytes('abc'))
    const imported = await importFileKey(fileKey)
    expect(new TextDecoder().decode(await decryptBytes(imported, encrypted))).toBe('abc')
  })

  it('adds exactly IV + tag bytes, and uses a fresh IV every time', async () => {
    const { key } = await generateFileKey()
    const a = await encryptBytes(key, bytes('same'))
    const b = await encryptBytes(key, bytes('same'))
    expect(a.length).toBe(4 + ENCRYPTION_OVERHEAD)
    expect(toBase64(a)).not.toBe(toBase64(b))
  })

  it('detects tampering', async () => {
    const { key } = await generateFileKey()
    const encrypted = await encryptBytes(key, bytes('important'))
    encrypted[encrypted.length - 1] ^= 1
    await expect(decryptBytes(key, encrypted)).rejects.toThrow()
  })
})

describe('file metadata', () => {
  it('encrypts name and type', async () => {
    const { key } = await generateFileKey()
    const encMeta = await encryptMeta(key, { name: 'تقرير.pdf', type: 'application/pdf' })
    expect(atob(encMeta)).not.toContain('pdf')
    expect(await decryptMeta(key, encMeta)).toEqual({ name: 'تقرير.pdf', type: 'application/pdf' })
  })

  it('sanitizes dangerous names', () => {
    expect(sanitizeFileName('../../etc/passwd')).toBe('passwd')
    expect(sanitizeFileName('C:\\Users\\x\\evil.exe')).toBe('evil.exe')
    expect(sanitizeFileName('a\u0000b.txt')).toBe('ab.txt')
    expect(sanitizeFileName('')).toBe('download')
    expect(sanitizeFileName(42)).toBe('download')
  })
})
