/**
 * GAMA Web Service Worker
 *
 * Enables offline functionality by caching all necessary resources.
 * After the first load, the application works completely offline.
 *
 * Cache strategy:
 * - App shell (HTML, CSS, JS): Cache-first
 * - Three.js library: Cache-first (CDN)
 * - Java JARs (CheerpJ): Cache-first with network fallback
 * - Model files: IndexedDB (separate from service worker)
 */

const CACHE_NAME = 'gama-web-v25';
const RUNTIME_CACHE = 'gama-runtime-v25';

// App shell files to pre-cache
const APP_SHELL = [
    './',
    './index.html',
    './css/style.css',
    './js/gama-runtime.js',
    './js/gama-compiler.js',
    './js/gama-renderer.js',
    './js/gama-session.js',
    './js/gama-app.js',
    './catalog.json'
];

// CDN resources to cache
const CDN_RESOURCES = [
    'https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js',
    'https://cdn.jsdelivr.net/npm/three@0.170.0/examples/jsm/controls/OrbitControls.js',
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.js',
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/codemirror.min.css',
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/mode/clike/clike.min.js',
    'https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16/theme/material-darker.min.css'
];

// Install: pre-cache app shell
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then((cache) => {
                console.log('[SW] Pre-caching app shell');
                return cache.addAll([...APP_SHELL, ...CDN_RESOURCES]);
            })
            .then(() => self.skipWaiting())
    );
});

// Activate: clean up old caches
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames
                    .filter((name) => name !== CACHE_NAME && name !== RUNTIME_CACHE)
                    .map((name) => {
                        console.log('[SW] Deleting old cache:', name);
                        return caches.delete(name);
                    })
            );
        }).then(() => self.clients.claim())
    );
});

// Fetch: serve from cache, fallback to network
self.addEventListener('fetch', (event) => {
    const { request } = event;
    const url = new URL(request.url);

    // Skip non-GET requests
    if (request.method !== 'GET') return;

    // Skip chrome-extension and other non-http schemes
    if (!url.protocol.startsWith('http')) return;

    // Java JAR files: cache with long expiration
    if (url.pathname.endsWith('.jar')) {
        event.respondWith(
            caches.open(RUNTIME_CACHE).then((cache) => {
                return cache.match(request).then((cached) => {
                    if (cached) return cached;
                    return fetch(request).then((response) => {
                        if (response.ok) {
                            cache.put(request, response.clone());
                        }
                        return response;
                    }).catch(() => {
                        // Return offline page for JAR requests
                        return new Response('JAR not available offline', {
                            status: 503,
                            statusText: 'Service Unavailable'
                        });
                    });
                });
            })
        );
        return;
    }

    // CheerpJ runtime files
    if (url.pathname.includes('cheerpj') || url.pathname.includes('.so')) {
        event.respondWith(
            caches.open(RUNTIME_CACHE).then((cache) => {
                return cache.match(request).then((cached) => {
                    if (cached) return cached;
                    return fetch(request).then((response) => {
                        if (response.ok) {
                            cache.put(request, response.clone());
                        }
                        return response;
                    });
                });
            })
        );
        return;
    }

    // App shell and CDN: cache-first
    event.respondWith(
        caches.match(request).then((cached) => {
            if (cached) return cached;

            return fetch(request).then((response) => {
                // Don't cache non-successful responses
                if (!response || response.status !== 200 || response.type !== 'basic') {
                    return response;
                }

                // Cache the response
                const responseToCache = response.clone();
                caches.open(CACHE_NAME).then((cache) => {
                    cache.put(request, responseToCache);
                });

                return response;
            }).catch(() => {
                // Offline fallback for navigation requests
                if (request.mode === 'navigate') {
                    return caches.match('./index.html');
                }
                return new Response('Offline', { status: 503 });
            });
        })
    );
});

// Handle messages from the main thread
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
