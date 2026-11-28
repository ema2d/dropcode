/// <reference lib="webworker" />
import { createHandlerBoundToURL, precacheAndRoute, type PrecacheEntry } from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'
import { FILE_NAME_HEADER, SHARE_CACHE, SHARE_TARGET_PATH, SHARED_FILE_URL } from './lib/share-target'

declare let self: ServiceWorkerGlobalScope & { __WB_MANIFEST: Array<PrecacheEntry | string> }

// Android "Share to Dropcode": the OS POSTs the file here. Keep it in a cache and open the app,
// which picks it up (see lib/shared-file.ts). Registered before Workbox so it sees the POST first.
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (event.request.method !== 'POST' || url.pathname !== SHARE_TARGET_PATH) return
  event.respondWith(
    (async () => {
      const form = await event.request.formData()
      const file = form.get('file')
      if (file instanceof File) {
        const cache = await caches.open(SHARE_CACHE)
        await cache.put(
          SHARED_FILE_URL,
          new Response(file, {
            headers: {
              [FILE_NAME_HEADER]: encodeURIComponent(file.name),
              'Content-Type': file.type || 'application/octet-stream',
            },
          }),
        )
      }
      return Response.redirect('/?shared=1', 303)
    })(),
  )
})

precacheAndRoute(self.__WB_MANIFEST)
// Deep links like /ABC23 work offline-first: always serve the app shell
registerRoute(new NavigationRoute(createHandlerBoundToURL('/index.html')))

self.addEventListener('install', () => void self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))
