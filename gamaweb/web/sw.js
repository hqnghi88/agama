/**
 * GAMA Web Service Worker v26
 *
 * Cache strategy:
 * - App shell (HTML, CSS, JS): Network-first (always fetch fresh, cache as fallback)
 * - CDN resources: Cache-first (stable versions)
 */

const CACHE_NAME = 'gama-web-v26';

// App shell files
const APP_SHELL = [
    './',
    './index.html',
    './css/style.css',
    './js/gama-runtime.js',
    './js/gama-compiler.js',
    './js/gama-renderer.js',
    './js/gama-session.js',
    './js/gama-websocket.js',
    './js/gama-server-session.js',
    './js/gama-app.js',
    './catalog.json'
];

// CDN resources (stable versions, cache-first is fine)
const CDN_RESOURCES = [
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.js',
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.css',
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/mode/clike/clike.min.js',
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/theme/material-darker.min.css'
];

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then((cache) => {
                console.log('[SW] Pre-caching CDN resources');
                return cache.addAll(CDN_RESOURCES);
            })
            .then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames
                    .filter((name) => name !== CACHE_NAME)
                    .map((name) => {
                        console.log('[SW] Deleting old cache:', name);
                        return caches.delete(name);
                    })
            );
        }).then(() => {
            // Notify all clients to reload on SW update
            return self.clients.matchAll().then((clients) => {
                clients.forEach((client) => {
                    client.postMessage({ type: 'SW_UPDATED', version: CACHE_NAME });
                });
            });
        }).then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', (event) => {
    const { request } = event;
    const url = new URL(request.url);

    if (request.method !== 'GET') return;
    if (!url.protocol.startsWith('http')) return;

    // CDN resources: cache-first
    if (url.hostname !== location.hostname) {
        event.respondWith(
            caches.match(request).then((cached) => {
                if (cached) return cached;
                return fetch(request).then((response) => {
                    if (response.ok) {
                        const clone = response.clone();
                        caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
                    }
                    return response;
                });
            })
        );
        return;
    }

    // Local app files: NETWORK-FIRST (always get fresh code)
    event.respondWith(
        fetch(request).then((response) => {
            if (response.ok) {
                const clone = response.clone();
                caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
            }
            return response;
        }).catch(() => {
            // Fallback to cache when offline
            return caches.match(request).then((cached) => {
                return cached || new Response('Offline', { status: 503 });
            });
        })
    );
});

self.addEventListener('message', (event) => {
    if (event.data === 'skipWaiting') {
        self.skipWaiting();
    }
    if (event.data === 'clearCache') {
        caches.keys().then((names) => {
            names.forEach((name) => caches.delete(name));
        });
    }
});
