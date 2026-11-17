/* 四象限日记 · Service Worker
 * 只缓存「页面外壳」：HTML / manifest / 图标 / 课程数据。
 * 换了 kcb-data.js（重新同步课表）后，同样要 +1 版本号才会生效。
 * 日记数据在 localStorage，照片在 IndexedDB，都不经过 SW。
 * 修改应用后请手动把 CACHE 版本号 +1，旧缓存会在 activate 时清掉。
 */
const CACHE = 'quad-diary-shell-v33';
const SHELL = [
  './',
  './index.html',
  './manifest.json',
  './kcb-data.js',
  './vendor/jszip.min.js',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/apple-touch-icon.png'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // 外部资源不拦截

  // 导航请求：网络优先（拿到新版本就顺手更新缓存），断网回退到缓存的页面外壳
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).then(res => {
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put('./index.html', copy));
        }
        return res;
      }).catch(() =>
        caches.match('./index.html').then(r => r || caches.match('./'))
      )
    );
    return;
  }

  // 静态资源：缓存优先，未命中才走网络并写入缓存
  event.respondWith(
    caches.match(req).then(hit => hit || fetch(req).then(res => {
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy));
      }
      return res;
    }))
  );
});
