import { FILE_NAME_HEADER, SHARE_CACHE, SHARED_FILE_URL } from './share-target'

/** Takes (and removes) a file shared to the app from Android's share sheet, if any. */
export async function takeSharedFile(): Promise<File | null> {
  if (!('caches' in window)) return null
  const cache = await caches.open(SHARE_CACHE)
  const response = await cache.match(SHARED_FILE_URL)
  if (!response) return null
  await cache.delete(SHARED_FILE_URL)
  const name = decodeURIComponent(response.headers.get(FILE_NAME_HEADER) ?? 'file')
  const blob = await response.blob()
  return new File([blob], name, { type: blob.type })
}
