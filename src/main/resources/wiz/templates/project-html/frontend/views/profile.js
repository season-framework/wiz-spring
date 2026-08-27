import { errorText, escapeHtml, formValues, statusBadge } from '../lib/ui.js';

export async function renderProfile({ api, shell, store }) {
  const profile = await api.get('/profile');
  shell.setContent(`
    <section class="page-heading"><div><span class="eyebrow">ACCOUNT</span><h1>내 프로필</h1><p>개인정보와 계정 비밀번호를 관리합니다.</p></div></section>
    <section class="profile-grid">
      <aside class="panel profile-summary"><span class="avatar profile-avatar">${escapeHtml((profile.name || profile.email).charAt(0))}</span><h2>${escapeHtml(profile.name)}</h2><p>${escapeHtml(profile.email)}</p>${statusBadge(profile.role)}<dl><div><dt>계정 ID</dt><dd>${escapeHtml(profile.id)}</dd></div><div><dt>가입일</dt><dd>${new Date(profile.createdAt).toLocaleDateString('ko-KR')}</dd></div></dl></aside>
      <div class="profile-forms">
        <section class="panel form-panel"><div class="panel-heading"><div><h2>기본 정보</h2><p>이름과 연락처를 수정할 수 있습니다.</p></div></div>
          <form id="profile-form" class="form-stack"><label>이메일<input value="${escapeHtml(profile.email)}" disabled></label><label>이름<input name="name" maxlength="100" required value="${escapeHtml(profile.name)}"></label><label>연락처<input name="mobile" maxlength="40" value="${escapeHtml(profile.mobile || '')}" placeholder="010-0000-0000"></label><div class="form-actions"><p class="form-error" id="profile-error" hidden></p><button class="primary-button" type="submit">프로필 저장</button></div></form>
        </section>
        <section class="panel form-panel"><div class="panel-heading"><div><h2>비밀번호 변경</h2><p>8자 이상의 새 비밀번호를 사용하세요.</p></div></div>
          <form id="password-form" class="form-stack"><label>현재 비밀번호<input name="currentPassword" type="password" autocomplete="current-password" required></label><label>새 비밀번호<input name="newPassword" type="password" minlength="8" maxlength="72" autocomplete="new-password" required></label><label>새 비밀번호 확인<input name="confirmation" type="password" minlength="8" autocomplete="new-password" required></label><div class="form-actions"><p class="form-error" id="password-error" hidden></p><button class="danger-button" type="submit">비밀번호 변경</button></div></form>
        </section>
      </div>
    </section>`);

  const profileForm = shell.page.querySelector('#profile-form');
  profileForm.addEventListener('submit', async event => {
    event.preventDefault(); const errorBox = profileForm.querySelector('#profile-error'); errorBox.hidden = true;
    try {
      const updated = await api.put('/profile', formValues(profileForm));
      store.setSession({ ...store.session, name: updated.name, email: updated.email, role: updated.role });
      shell.toast('프로필을 저장했습니다.');
    } catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
  });
  const passwordForm = shell.page.querySelector('#password-form');
  passwordForm.addEventListener('submit', async event => {
    event.preventDefault(); const values = formValues(passwordForm); const errorBox = passwordForm.querySelector('#password-error'); errorBox.hidden = true;
    if (values.newPassword !== values.confirmation) { errorBox.textContent = '새 비밀번호가 서로 일치하지 않습니다.'; errorBox.hidden = false; return; }
    try {
      await api.put('/profile/password', { currentPassword: values.currentPassword, newPassword: values.newPassword });
      passwordForm.reset(); shell.toast('비밀번호를 변경했습니다.');
    } catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
  });
}
