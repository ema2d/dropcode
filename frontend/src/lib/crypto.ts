import { fromBase64, toBase64 } from './base64'

/**
 * End-to-end encryption in the browser (Web Crypto). The server and R2 only ever see ciphertext.
 *
 * Protected share: PBKDF2-SHA256(password, salt, 600k) -> 64 bytes.
 *   first 32 = AES-256-GCM key (never leaves the device)
 *   last 32  = authToken (sent to the server, stored there as an Argon2id hash)
 * Unprotected share: random AES key, stored on the server so anyone with the code can decrypt.
 *
 * Encrypted payload layout: 12-byte random IV || AES-GCM ciphertext (includes the 16-byte tag).
 */

export const PBKDF2_ITERATIONS = 600_000
export const SALT_BYTES = 16
const IV_BYTES = 12
const TAG_BYTES = 16
/** Bytes added to the file by encryption (IV + GCM tag). */
export const ENCRYPTION_OVERHEAD = IV_BYTES + TAG_BYTES

const encoder = new TextEncoder()
const decoder = new TextDecoder()

export interface FileMeta {
  name: string
  type: string
}

export function randomSalt(): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(SALT_BYTES))
}

export async function deriveFromPassword(
  password: string,
  salt: Uint8Array<ArrayBuffer>,
  iterations = PBKDF2_ITERATIONS,
): Promise<{ key: CryptoKey; authToken: string }> {
  const material = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveBits'])
  const bits = new Uint8Array(
    await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, material, 512),
  )
  const key = await crypto.subtle.importKey('raw', bits.slice(0, 32), 'AES-GCM', false, ['encrypt', 'decrypt'])
  return { key, authToken: toBase64(bits.slice(32)) }
}

export async function generateFileKey(): Promise<{ key: CryptoKey; fileKey: string }> {
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', key))
  return { key, fileKey: toBase64(raw) }
}

export function importFileKey(fileKey: string): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', fromBase64(fileKey), 'AES-GCM', false, ['encrypt', 'decrypt'])
}

export async function encryptBytes(key: CryptoKey, data: BufferSource): Promise<Uint8Array<ArrayBuffer>> {
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES))
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, data))
  const out = new Uint8Array(IV_BYTES + ciphertext.length)
  out.set(iv)
  out.set(ciphertext, IV_BYTES)
  return out
}

/** Throws if the key is wrong or the data was tampered with (GCM authentication). */
export async function decryptBytes(key: CryptoKey, payload: Uint8Array<ArrayBuffer>): Promise<ArrayBuffer> {
  const iv = payload.slice(0, IV_BYTES)
  return crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, payload.slice(IV_BYTES))
}

export async function encryptMeta(key: CryptoKey, meta: FileMeta): Promise<string> {
  return toBase64(await encryptBytes(key, encoder.encode(JSON.stringify(meta))))
}

export async function decryptMeta(key: CryptoKey, encMeta: string): Promise<FileMeta> {
  const json = decoder.decode(await decryptBytes(key, fromBase64(encMeta)))
  const parsed = JSON.parse(json) as Partial<FileMeta>
  return { name: sanitizeFileName(parsed.name), type: typeof parsed.type === 'string' ? parsed.type : '' }
}

/** The name comes from the uploader: strip path parts and control characters before saving. */
export function sanitizeFileName(name: unknown): string {
  const base = typeof name === 'string' ? name.split(/[\\/]/).pop() ?? '' : ''
  // eslint-disable-next-line no-control-regex
  const clean = base.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 200)
  return clean || 'download'
}
