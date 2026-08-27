import { useEffect, useState } from 'react';
import { navigate } from '../router.js';

const navigation = [
  ['dashboard', '▦', '대시보드'],
  ['members', '♙', '멤버'],
  ['posts', '▤', '게시글'],
  ['chat', '◌', '팀 채팅'],
  ['profile', '⚙', '내 프로필'],
];

export default function AppShell({ user, route, onLogout, children }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [theme, setTheme] = useState(() => localStorage.getItem('wiz-theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('wiz-theme', theme); }, [theme]);

  return <div className="app-frame">
    <button className="mobile-menu icon-button" aria-label="메뉴 열기" onClick={() => setMenuOpen(true)}>☰</button>
    <button className={`sidebar-backdrop ${menuOpen ? 'visible' : ''}`} aria-label="메뉴 닫기" onClick={() => setMenuOpen(false)} />
    <aside className={`sidebar ${menuOpen ? 'open' : ''}`}>
      <div className="brand"><span className="brand-mark">W</span><span>__WIZ_PROJECT_NAME__</span></div>
      <nav aria-label="주 메뉴">{navigation.map(([path, icon, label]) => <a key={path} href={`#/${path}`} className={route === path ? 'active' : ''} onClick={() => setMenuOpen(false)}><span>{icon}</span><span>{label}</span></a>)}</nav>
      <div className="sidebar-footer">
        <button className="theme-button" onClick={() => setTheme(value => value === 'dark' ? 'light' : 'dark')}><span>{theme === 'dark' ? '☀' : '☾'}</span>{theme === 'dark' ? '라이트 모드' : '다크 모드'}</button>
        <div className="user-card"><span className="avatar">{initials(user.name)}</span><span className="user-copy"><strong>{user.name}</strong><small>{user.email}</small></span><button className="icon-button" title="로그아웃" aria-label="로그아웃" onClick={onLogout}>↪</button></div>
      </div>
    </aside>
    <main className="content">{children}</main>
  </div>;
}

export const initials = name => String(name || '?').slice(0, 2).toUpperCase();
