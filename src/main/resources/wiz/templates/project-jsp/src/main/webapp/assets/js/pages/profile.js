import { apiClient } from '../api.js';
import { errorText, escapeHtml, formValues, showFatal, statusBadge, toast } from '../ui.js';

try {
    const api = await apiClient();
    const profile = await api.get('/profile');
    const summary = document.querySelector('#profile-summary');
    summary.innerHTML = `<span class="avatar profile-avatar">${escapeHtml((profile.name || profile.email).charAt(0))}</span><h2>${escapeHtml(profile.name)}</h2><p>${escapeHtml(profile.email)}</p>${statusBadge(profile.role)}<dl><div><dt>계정 ID</dt><dd>${escapeHtml(profile.id)}</dd></div><div><dt>가입일</dt><dd>${new Date(profile.createdAt).toLocaleDateString('ko-KR')}</dd></div></dl>`;
    const profileForm = document.querySelector('#profile-form');
    profileForm.elements.email.value = profile.email;
    profileForm.elements.name.value = profile.name;
    profileForm.elements.mobile.value = profile.mobile || '';
    profileForm.addEventListener('submit', async event => {
        event.preventDefault(); const errorBox = document.querySelector('#profile-error'); errorBox.hidden = true;
        try { const updated = await api.put('/profile', formValues(profileForm)); summary.querySelector('h2').textContent = updated.name; toast('프로필을 저장했습니다.'); }
        catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
    });
    const passwordForm = document.querySelector('#password-form');
    passwordForm.addEventListener('submit', async event => {
        event.preventDefault(); const values = formValues(passwordForm); const errorBox = document.querySelector('#password-error'); errorBox.hidden = true;
        if (values.newPassword !== values.confirmation) { errorBox.textContent = '새 비밀번호가 서로 일치하지 않습니다.'; errorBox.hidden = false; return; }
        try { await api.put('/profile/password', { currentPassword: values.currentPassword, newPassword: values.newPassword }); passwordForm.reset(); toast('비밀번호를 변경했습니다.'); }
        catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
    });
} catch (error) {
    showFatal(error);
}
