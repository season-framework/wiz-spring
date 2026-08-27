import { useEffect, useState } from 'react';

const supported = new Set(['login', 'dashboard', 'members', 'posts', 'profile', 'chat']);

function currentRoute() {
  const route = window.location.hash.replace(/^#\/?/, '').split(/[/?]/)[0] || 'dashboard';
  return supported.has(route) ? route : 'dashboard';
}

export function useHashRoute() {
  const [route, setRoute] = useState(currentRoute);
  useEffect(() => {
    const update = () => setRoute(currentRoute());
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  return route;
}

export function navigate(route) {
  window.location.hash = `#/${supported.has(route) ? route : 'dashboard'}`;
}
