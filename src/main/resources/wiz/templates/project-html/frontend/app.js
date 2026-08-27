import { ApiClient, loadRuntimeConfiguration } from './lib/api.js';
import { createRouter } from './lib/router.js';
import { createStore } from './lib/store.js';
import { createShell, renderFatalError } from './lib/ui.js';
import { renderAccess } from './views/access.js';
import { renderChat } from './views/chat.js';
import { renderDashboard } from './views/dashboard.js';
import { renderMembers } from './views/members.js';
import { renderPostEditor, renderPosts } from './views/posts.js';
import { renderProfile } from './views/profile.js';

const routes = {
  access: renderAccess,
  dashboard: renderDashboard,
  posts: renderPosts,
  'post-new': renderPostEditor,
  'post-detail': renderPostEditor,
  members: renderMembers,
  profile: renderProfile,
  chat: renderChat,
};

let activeCleanup = null;

async function bootstrap() {
  const configuration = await loadRuntimeConfiguration();
  const api = new ApiClient(configuration.apiPrefix);
  const store = createStore();
  const shell = createShell(store);
  const router = createRouter();

  try {
    store.setSession(await api.get('/auth/session'));
  } catch (error) {
    if (error.status !== 401) throw error;
    store.setSession({ authenticated: false });
  }

  shell.show();
  shell.onLogout(async () => {
    await api.post('/auth/logout');
    store.setSession({ authenticated: false });
    router.go('/access');
  });

  router.subscribe(async route => {
    activeCleanup?.();
    activeCleanup = null;

    if (!store.session.authenticated && route.name !== 'access') {
      router.go('/access', true);
      return;
    }
    if (store.session.authenticated && route.name === 'access') {
      router.go('/dashboard', true);
      return;
    }

    shell.setRoute(route.name);
    const render = routes[route.name] ?? renderDashboard;
    try {
      const cleanup = await render({ api, configuration, route, router, shell, store });
      activeCleanup = typeof cleanup === 'function' ? cleanup : null;
      shell.focusPage();
    } catch (error) {
      if (error.status === 401) {
        store.setSession({ authenticated: false });
        router.go('/access', true);
        return;
      }
      shell.showPageError(error);
    }
  });

  router.start(store.session.authenticated ? '/dashboard' : '/access');
}

bootstrap().catch(error => renderFatalError(error));
