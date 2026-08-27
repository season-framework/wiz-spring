import { errorText, formValues } from '../lib/ui.js';

export async function renderAccess({ api, router, shell, store }) {
  shell.setContent(`
    <section class="access-page">
      <div class="access-intro">
        <span class="eyebrow">STANDARD SPRING + VANILLA WEB</span>
        <h1>작게 시작해도<br>구조는 선명하게.</h1>
        <p>의존성 없는 브라우저 모듈, 표준 Spring API, 런타임 prefix와 세션 인증을 한 프로젝트에서 확인하세요.</p>
        <ul class="feature-list">
          <li><span>✓</span>Hash-based client routing</li>
          <li><span>✓</span>CRUD, validation and pagination</li>
          <li><span>✓</span>Server-Sent Events chat</li>
        </ul>
      </div>
      <div class="login-card">
        <div class="login-heading">
          <span class="brand-mark large">W</span>
          <div><h2>다시 만나 반갑습니다</h2><p>샘플 계정으로 바로 둘러볼 수 있습니다.</p></div>
        </div>
        <form id="login-form" class="form-stack">
          <label>이메일<input name="email" type="email" autocomplete="username" value="admin@example.com" required></label>
          <label>비밀번호<input name="password" type="password" autocomplete="current-password" value="admin1234" required></label>
          <p class="form-error" id="login-error" hidden></p>
          <button class="primary-button large-button" type="submit">로그인</button>
        </form>
        <button class="demo-account" id="demo-account" type="button">
          <span class="avatar">A</span><span><strong>Demo administrator</strong><small>admin@example.com / admin1234</small></span>
        </button>
      </div>
    </section>`);

  const form = shell.page.querySelector('#login-form');
  const errorBox = shell.page.querySelector('#login-error');
  shell.page.querySelector('#demo-account').addEventListener('click', () => {
    form.elements.email.value = 'admin@example.com';
    form.elements.password.value = 'admin1234';
    form.elements.email.focus();
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    button.textContent = '로그인 중…';
    errorBox.hidden = true;
    try {
      const session = await api.post('/auth/login', formValues(form));
      store.setSession(session);
      shell.toast(`${session.name}님, 환영합니다.`);
      router.go('/dashboard');
    } catch (error) {
      errorBox.textContent = errorText(error);
      errorBox.hidden = false;
    } finally {
      button.disabled = false;
      button.textContent = '로그인';
    }
  });
}
