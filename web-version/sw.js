// Lets the app start without a connection once it has been loaded. Browsers
// only allow this on https (or localhost); on plain http it is simply not used.
//
// It fetches from this site and nowhere else: the newest copy when the server
// can be reached, the stored copy when it cannot.

const CACHE = 'looseleaf-v1';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return;
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const fresh = await fetch(e.request);
      if (fresh.ok) cache.put(e.request, fresh.clone());
      return fresh;
    } catch (err) {
      const stored = await cache.match(e.request, { ignoreSearch: true });
      if (stored) return stored;
      throw err;
    }
  })());
});
