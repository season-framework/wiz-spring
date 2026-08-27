import { apiClient } from '../api.js';
import { emptyPanel, errorText, escapeHtml, formatDate, formValues, showFatal, statusBadge, toast } from '../ui.js';

function cards(members) {
    if (!members.length) return emptyPanel('등록된 멤버가 없습니다.', '첫 팀 멤버를 초대해 보세요.');
    return `<div class="member-grid">${members.map(member => `<article class="member-card" data-member-id="${escapeHtml(member.id)}"><div class="member-main"><span class="avatar large-avatar">${escapeHtml((member.name || member.email).charAt(0))}</span><div><strong>${escapeHtml(member.name || '이름 미지정')}</strong><small>${escapeHtml(member.email)}</small></div></div><div class="member-meta">${statusBadge(member.role)}<span>${escapeHtml(member.mobile || '연락처 미등록')}</span></div><div class="card-actions"><button class="text-button" data-action="detail" type="button">상세</button><button class="text-button danger-text" data-action="delete" type="button">삭제</button></div></article>`).join('')}</div>`;
}

try {
    const api = await apiClient();
    let members = await api.get('/members');
    const list = document.querySelector('#member-list');
    const panel = document.querySelector('#invite-panel');
    const form = document.querySelector('#invite-form');
    const dialog = document.querySelector('#member-dialog');
    const repaint = () => { list.innerHTML = cards(members); };
    repaint();
    document.querySelector('#invite-toggle').addEventListener('click', () => { panel.hidden = false; form.elements.email.focus(); });
    document.querySelector('#invite-cancel').addEventListener('click', () => { panel.hidden = true; form.reset(); });
    dialog.querySelector('.dialog-close').addEventListener('click', () => dialog.close());
    form.addEventListener('submit', async event => {
        event.preventDefault(); const errorBox = document.querySelector('#invite-error'); errorBox.hidden = true;
        try {
            const member = await api.post('/members', formValues(form));
            members = [member, ...members]; repaint(); form.reset(); panel.hidden = true; toast(`${member.name || member.email} 멤버를 초대했습니다.`);
        } catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
    });
    list.addEventListener('click', async event => {
        const action = event.target.closest('[data-action]')?.dataset.action;
        const card = event.target.closest('[data-member-id]');
        if (!action || !card) return;
        const id = card.dataset.memberId;
        try {
            if (action === 'delete') {
                if (!confirm('이 멤버를 삭제할까요?')) return;
                await api.delete(`/members/${encodeURIComponent(id)}`); members = members.filter(member => member.id !== id); repaint(); toast('멤버를 삭제했습니다.'); return;
            }
            const member = await api.get(`/members/${encodeURIComponent(id)}`);
            dialog.querySelector('#member-detail').innerHTML = `<span class="avatar dialog-avatar">${escapeHtml((member.name || member.email).charAt(0))}</span><h2>${escapeHtml(member.name || '이름 미지정')}</h2><p>${escapeHtml(member.email)}</p><dl class="detail-list"><div><dt>역할</dt><dd>${statusBadge(member.role)}</dd></div><div><dt>연락처</dt><dd>${escapeHtml(member.mobile || '-')}</dd></div><div><dt>가입일</dt><dd>${formatDate(member.createdAt)}</dd></div><div><dt>최근 수정</dt><dd>${formatDate(member.updatedAt)}</dd></div></dl>`;
            dialog.showModal();
        } catch (error) { toast(errorText(error), 'error'); }
    });
} catch (error) {
    showFatal(error);
}
