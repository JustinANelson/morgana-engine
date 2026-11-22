// Morgana ORPG browser client: service worker.
//
// Purpose: a phone on a poor or briefly-lost connection can still open the game page, because the page's own files
// (index.html, app.js, scripts, the UI skin) are kept after each successful load. It is NETWORK-FIRST: whenever the
// server answers, the player gets the current build (the server rebuilds the client under the same file names, so a
// stale copy would show a confusing "update your client" mismatch); the saved copy is only the fallback when the
// network is down or too slow (SLOW_MS).
//
// Deliberately NOT handled here, so they always go to the network or to their own caches: the game's art
// (`assets/<manifest path>`, kept in IndexedDB by the client), the game wiki (`wiki/`), batch requests (anything with a
// query string), non-GET requests, and WebSocket traffic (service workers never see it).
'use strict';

var CACHE = 'morgana-shell-v1';
var SLOW_MS = 4000;
var SCOPE_PATH = new URL(self.registration.scope).pathname;

// Paths, relative to the worker's scope, that make up the app shell.
function isShell(rel) {
    return rel === '' || rel === 'index.html' || rel === 'app.js' || rel === 'manifest.webmanifest' ||
        rel.indexOf('icons/') === 0 || rel.indexOf('scripts/') === 0 ||
        rel.indexOf('assets/com/') === 0 || rel === 'assets/startup-logo.png';
}

self.addEventListener('install', function () { self.skipWaiting(); });

self.addEventListener('activate', function (event) {
    event.waitUntil(
        caches.keys()
            .then(function (names) {
                return Promise.all(names.filter(function (n) { return n !== CACHE; }).map(function (n) { return caches.delete(n); }));
            })
            .then(function () { return self.clients.claim(); })
    );
});

self.addEventListener('fetch', function (event) {
    var request = event.request;
    if (request.method !== 'GET' || request.headers.has('range')) return;
    var url = new URL(request.url);
    if (url.origin !== self.location.origin || url.search) return;
    if (url.pathname.indexOf(SCOPE_PATH) !== 0) return;
    var rel = url.pathname.substring(SCOPE_PATH.length);
    if (!isShell(rel)) return;
    event.respondWith(networkFirst(request, rel === '' ? new Request(SCOPE_PATH + 'index.html') : request));
});

function networkFirst(request, cacheKey) {
    return new Promise(function (resolve) {
        var settled = false;
        function fromCache() {
            return caches.match(cacheKey).then(function (hit) { return hit || null; });
        }
        var timer = setTimeout(function () {
            // Slow network: serve the saved copy now if there is one; the network answer still refreshes the cache.
            fromCache().then(function (hit) { if (hit && !settled) { settled = true; resolve(hit); } });
        }, SLOW_MS);
        fetch(request).then(function (response) {
            clearTimeout(timer);
            if (response && response.ok) {
                var copy = response.clone();
                caches.open(CACHE).then(function (cache) { return cache.put(cacheKey, copy); }).catch(function () {});
            }
            if (!settled) { settled = true; resolve(response); }
        }).catch(function () {
            clearTimeout(timer);
            fromCache().then(function (hit) {
                if (settled) return;
                settled = true;
                resolve(hit || Response.error());
            });
        });
    });
}
