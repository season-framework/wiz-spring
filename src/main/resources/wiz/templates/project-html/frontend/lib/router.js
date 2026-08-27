export function parseRoute(hash) {
  const raw = String(hash || '').replace(/^#/, '').trim() || '/';
  const path = `/${raw.replace(/^\/+|\/+$/g, '')}`;
  const segments = path.split('/').filter(Boolean).map(decodeURIComponent);
  if (!segments.length) return { name: 'dashboard', path: '/dashboard', params: {} };
  if (segments[0] === 'access') return { name: 'access', path, params: {} };
  if (segments[0] === 'dashboard') return { name: 'dashboard', path, params: {} };
  if (segments[0] === 'members') return { name: 'members', path, params: {} };
  if (segments[0] === 'profile') return { name: 'profile', path, params: {} };
  if (segments[0] === 'chat') return { name: 'chat', path, params: {} };
  if (segments[0] === 'posts' && segments[1] === 'new') return { name: 'post-new', path, params: {} };
  if (segments[0] === 'posts' && segments[1]) {
    return { name: 'post-detail', path, params: { id: segments[1] } };
  }
  if (segments[0] === 'posts') return { name: 'posts', path, params: {} };
  return { name: 'dashboard', path: '/dashboard', params: {} };
}

export function createRouter(browserWindow = window) {
  const listeners = new Set();
  const notify = () => {
    const route = parseRoute(browserWindow.location.hash);
    listeners.forEach(listener => listener(route));
  };
  return {
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    start(fallback) {
      browserWindow.addEventListener('hashchange', notify);
      if (!browserWindow.location.hash) this.go(fallback, true);
      else notify();
    },
    go(path, replace = false) {
      const hash = `#${path.startsWith('/') ? path : `/${path}`}`;
      if (replace) {
        browserWindow.history.replaceState(null, '', hash);
        notify();
      } else if (browserWindow.location.hash !== hash) {
        browserWindow.location.hash = hash;
      } else {
        notify();
      }
    },
  };
}
