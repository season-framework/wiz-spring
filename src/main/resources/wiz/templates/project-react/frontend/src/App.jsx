import { useEffect, useState } from 'react';
import { api, ApiError } from './api/client.js';
import { useHashRoute, navigate } from './router.js';
import AppShell from './layout/AppShell.jsx';
import LoginPage from './pages/LoginPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import MembersPage from './pages/MembersPage.jsx';
import PostsPage from './pages/PostsPage.jsx';
import ProfilePage from './pages/ProfilePage.jsx';
import ChatPage from './pages/ChatPage.jsx';

const pages = {
  dashboard: DashboardPage,
  members: MembersPage,
  posts: PostsPage,
  profile: ProfilePage,
  chat: ChatPage,
};

export default function App() {
  const route = useHashRoute();
  const [session, setSession] = useState({ loading: true, user: null });

  useEffect(() => {
    let active = true;
    api('/auth/session')
      .then(value => active && setSession({ loading: false, user: normalizeUser(value) }))
      .catch(error => {
        if (!active) return;
        if (error instanceof ApiError && error.status === 401) setSession({ loading: false, user: null });
        else setSession({ loading: false, user: null, error: error.message });
      });
    return () => { active = false; };
  }, []);

  if (session.loading) return <div className="splash"><span className="brand-mark">W</span><p>프로젝트를 준비하고 있습니다…</p></div>;
  if (!session.user) return <LoginPage initialError={session.error} onLogin={value => {
    setSession({ loading: false, user: normalizeUser(value) });
    navigate(route === 'login' ? 'dashboard' : route);
  }} />;

  const Page = pages[route] ?? DashboardPage;
  return (
    <AppShell user={session.user} route={route} onLogout={async () => {
      try { await api('/auth/logout', { method: 'POST' }); }
      finally { setSession({ loading: false, user: null }); navigate('login'); }
    }}>
      <Page user={session.user} onUserChange={user => setSession({ loading: false, user })} />
    </AppShell>
  );
}

function normalizeUser(value) {
  if (!value || value.authenticated === false) return null;
  const user = value.user ?? value.profile ?? value;
  if (!user.email) return null;
  return { id: user.id, email: user.email, name: user.name || user.email.split('@')[0], role: user.role };
}
