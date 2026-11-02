// Service worker: gör webbappen installerbar och användbar offline.
// Byt CACHE-versionen vid varje ny utgåva så att användarna får uppdateringen.
const CACHE = 'sign-web-1.0';
const ASSETS = [
  './', 'index.html', 'manifest.webmanifest', 'css/app.css',
  'js/app.js', 'js/i18n.js', 'js/store.js', 'js/seal.js', 'js/composer.js', 'js/signpad.js', 'js/documents.js',
  'js/exporter.js', 'js/updates.js', 'js/version.js', 'js/native.js',
  'vendor/pdf.min.mjs', 'vendor/pdf.worker.min.mjs', 'vendor/pdf-lib.min.js', 'vendor/jszip.min.js',
  'vendor/docx-preview.min.js', 'vendor/html2canvas.min.js',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/logo.png',
  'fonts/cinzel_bold.ttf', 'fonts/jakarta_regular.ttf', 'fonts/jakarta_medium.ttf', 'fonts/jakarta_bold.ttf',
];
self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS))));
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())
));
self.addEventListener('message', e => { if (e.data === 'SKIP_WAITING') self.skipWaiting(); });
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  // Uppdateringslistan hämtas alltid färsk från nätet
  if (url.pathname.endsWith('changelog.json')) {
    e.respondWith(fetch(e.request).catch(() => caches.match('changelog.json')));
    return;
  }
  e.respondWith(caches.match(e.request, { ignoreSearch: true }).then(r => r || fetch(e.request).then(res => {
    if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); }
    return res;
  }).catch(() => e.request.mode === 'navigate' ? caches.match('index.html') : undefined)));
});
