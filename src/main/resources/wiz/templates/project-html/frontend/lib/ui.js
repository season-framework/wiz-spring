export function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

export function formatDate(value, options = {}) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: options.dateOnly ? undefined : '2-digit',
    minute: options.dateOnly ? undefined : '2-digit',
  }).format(date);
}

export function statusBadge(status) {
  const value = String(status || 'unknown').toLowerCase();
  const labels = { published: '공개', draft: '초안', archived: '보관', admin: '관리자', editor: '편집자', user: '사용자', viewer: '조회자' };
  return `<span class="status status-${escapeHtml(value)}">${escapeHtml(labels[value] || value)}</span>`;
}

export function formValues(form) {
  return Object.fromEntries(new FormData(form).entries());
}

export function errorText(error) {
  const fields = Object.values(error?.fieldErrors ?? {});
  return fields.length ? fields.join(' ') : error?.message || '요청을 처리하지 못했습니다.';
}

export function loadingPanel(label = '데이터를 불러오는 중입니다.') {
  return `<div class="loading-panel"><span class="spinner"></span><p>${escapeHtml(label)}</p></div>`;
}

export function emptyPanel(title, description) {
  return `<div class="empty-panel"><span class="empty-icon">◇</span><h3>${escapeHtml(title)}</h3><p>${escapeHtml(description)}</p></div>`;
}

export function createShell(store) {
  const boot = document.querySelector('#boot-screen');
  const app = document.querySelector('#app');
  const page = document.querySelector('#page');
  const sidebar = document.querySelector('#sidebar');
  const backdrop = document.querySelector('#sidebar-backdrop');
  const nav = document.querySelector('#primary-nav');
  const accountName = document.querySelector('#account-name');
  const accountEmail = document.querySelector('#account-email');
  const accountAvatar = document.querySelector('#account-avatar');
  const toastRegion = document.querySelector('#toast-region');
  let logoutHandler = null;

  const closeSidebar = () => {
    sidebar.classList.remove('is-open');
    backdrop.hidden = true;
  };
  document.querySelector('#sidebar-toggle').addEventListener('click', () => {
    sidebar.classList.add('is-open');
    backdrop.hidden = false;
  });
  backdrop.addEventListener('click', closeSidebar);
  nav.addEventListener('click', closeSidebar);
  document.querySelector('#theme-toggle').addEventListener('click', () => store.toggleTheme());
  document.querySelector('#mobile-theme-toggle').addEventListener('click', () => store.toggleTheme());
  document.querySelector('#logout-button').addEventListener('click', async event => {
    const button = event.currentTarget;
    button.disabled = true;
    try { await logoutHandler?.(); }
    catch (error) { showToast(errorText(error), 'error'); }
    finally { button.disabled = false; }
  });

  store.subscribe(state => {
    document.documentElement.dataset.theme = state.theme;
    const session = state.session;
    accountName.textContent = session.name || 'Guest';
    accountEmail.textContent = session.email || '로그인이 필요합니다';
    accountAvatar.textContent = (session.name || session.email || '?').charAt(0).toUpperCase();
  });

  function showToast(message, type = 'success') {
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    toastRegion.append(toast);
    setTimeout(() => toast.remove(), 3600);
  }

  return {
    page,
    show() { boot.hidden = true; app.hidden = false; },
    onLogout(handler) { logoutHandler = handler; },
    setRoute(routeName) {
      const access = routeName === 'access';
      document.body.classList.toggle('access-route', access);
      const activeRoute = routeName.startsWith('post-') ? 'posts' : routeName;
      nav.querySelectorAll('[data-route]').forEach(link => {
        link.classList.toggle('active', link.dataset.route === activeRoute);
      });
      closeSidebar();
      page.innerHTML = loadingPanel();
    },
    setContent(markup) { page.innerHTML = markup; },
    focusPage() { page.focus({ preventScroll: true }); },
    showPageError(error) {
      page.innerHTML = `<section class="content-narrow">${emptyPanel('화면을 불러오지 못했습니다.', errorText(error))}</section>`;
      showToast(errorText(error), 'error');
    },
    toast: showToast,
    confirm(message) { return window.confirm(message); },
  };
}

export function renderFatalError(error) {
  document.querySelector('#boot-screen').innerHTML = `
    <div class="fatal-error">
      <span class="empty-icon">!</span>
      <h1>애플리케이션을 시작하지 못했습니다.</h1>
      <p>${escapeHtml(error?.message || error)}</p>
      <button type="button" onclick="location.reload()">다시 시도</button>
    </div>`;
}
