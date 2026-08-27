import { emptyPanel, errorText, escapeHtml, formatDate, formValues, statusBadge } from '../lib/ui.js';

function memberCards(members) {
  if (!members.length) return emptyPanel('등록된 멤버가 없습니다.', '첫 팀 멤버를 초대해 보세요.');
  return `<div class="member-grid">${members.map(member => `
    <article class="member-card" data-member-id="${escapeHtml(member.id)}">
      <div class="member-main"><span class="avatar large-avatar">${escapeHtml((member.name || member.email).charAt(0))}</span>
      <div><strong>${escapeHtml(member.name || '이름 미지정')}</strong><small>${escapeHtml(member.email)}</small></div></div>
      <div class="member-meta">${statusBadge(member.role)}<span>${escapeHtml(member.mobile || '연락처 미등록')}</span></div>
      <div class="card-actions"><button class="text-button" data-action="detail" type="button">상세</button><button class="text-button danger-text" data-action="delete" type="button">삭제</button></div>
    </article>`).join('')}</div>`;
}

export async function renderMembers({ api, shell }) {
  let members = await api.get('/members');
  if (!Array.isArray(members)) members = [];
  shell.setContent(`
    <section class="page-heading"><div><span class="eyebrow">TEAM</span><h1>멤버</h1><p>역할을 확인하고 새 팀원을 초대하세요.</p></div><button class="primary-button" id="invite-toggle" type="button">멤버 초대</button></section>
    <section class="panel form-panel" id="invite-panel" hidden>
      <div class="panel-heading"><div><h2>새 멤버 초대</h2><p>초기 비밀번호는 <code>welcome1</code>입니다.</p></div></div>
      <form id="invite-form" class="form-grid">
        <label>이메일<input name="email" type="email" required placeholder="member@example.com"></label>
        <label>이름<input name="name" maxlength="100" placeholder="홍길동"></label>
        <label>역할<select name="role"><option value="user">사용자</option><option value="editor">편집자</option><option value="viewer">조회자</option><option value="admin">관리자</option></select></label>
        <div class="form-actions"><p class="form-error" id="invite-error" hidden></p><button class="secondary-button" id="invite-cancel" type="button">취소</button><button class="primary-button" type="submit">초대</button></div>
      </form>
    </section>
    <section id="member-list">${memberCards(members)}</section>
    <dialog class="detail-dialog" id="member-dialog"><button class="dialog-close" type="button" aria-label="닫기">×</button><div id="member-detail"></div></dialog>`);

  const panel = shell.page.querySelector('#invite-panel');
  const form = shell.page.querySelector('#invite-form');
  const list = shell.page.querySelector('#member-list');
  const dialog = shell.page.querySelector('#member-dialog');
  shell.page.querySelector('#invite-toggle').addEventListener('click', () => { panel.hidden = false; form.elements.email.focus(); });
  shell.page.querySelector('#invite-cancel').addEventListener('click', () => { panel.hidden = true; form.reset(); });
  dialog.querySelector('.dialog-close').addEventListener('click', () => dialog.close());

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const errorBox = form.querySelector('#invite-error');
    errorBox.hidden = true;
    try {
      const created = await api.post('/members', formValues(form));
      members = [created, ...members];
      list.innerHTML = memberCards(members);
      form.reset(); panel.hidden = true;
      shell.toast(`${created.name || created.email} 멤버를 초대했습니다.`);
    } catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
  });

  list.addEventListener('click', async event => {
    const action = event.target.closest('[data-action]')?.dataset.action;
    const card = event.target.closest('[data-member-id]');
    if (!action || !card) return;
    const id = card.dataset.memberId;
    if (action === 'delete') {
      if (!shell.confirm('이 멤버를 삭제할까요?')) return;
      try {
        await api.delete(`/members/${encodeURIComponent(id)}`);
        members = members.filter(member => member.id !== id);
        list.innerHTML = memberCards(members);
        shell.toast('멤버를 삭제했습니다.');
      } catch (error) { shell.toast(errorText(error), 'error'); }
      return;
    }
    try {
      const member = await api.get(`/members/${encodeURIComponent(id)}`);
      dialog.querySelector('#member-detail').innerHTML = `
        <span class="avatar dialog-avatar">${escapeHtml((member.name || member.email).charAt(0))}</span>
        <h2>${escapeHtml(member.name || '이름 미지정')}</h2><p>${escapeHtml(member.email)}</p>
        <dl class="detail-list"><div><dt>역할</dt><dd>${statusBadge(member.role)}</dd></div><div><dt>연락처</dt><dd>${escapeHtml(member.mobile || '-')}</dd></div><div><dt>가입일</dt><dd>${formatDate(member.createdAt)}</dd></div><div><dt>최근 수정</dt><dd>${formatDate(member.updatedAt)}</dd></div></dl>`;
      dialog.showModal();
    } catch (error) { shell.toast(errorText(error), 'error'); }
  });
}
