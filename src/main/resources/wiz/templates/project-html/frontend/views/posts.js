import { emptyPanel, errorText, escapeHtml, formatDate, formValues, statusBadge } from '../lib/ui.js';

function queryString(filters) {
  const query = new URLSearchParams();
  if (filters.text) query.set('text', filters.text);
  if (filters.category) query.set('category', filters.category);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return query.toString();
}

function postRows(page) {
  if (!page.items?.length) return emptyPanel('검색 결과가 없습니다.', '검색어나 카테고리를 바꾸거나 새 게시물을 작성해 보세요.');
  return `<div class="post-list">${page.items.map(post => `
    <a class="post-row" href="#/posts/${encodeURIComponent(post.id)}">
      <span class="post-title"><strong>${escapeHtml(post.title)}</strong><small>${escapeHtml(post.summary || '내용 미리보기가 없습니다.')}</small></span>
      <span class="category-chip">${escapeHtml(post.category || '일반')}</span>
      <span class="post-author">${escapeHtml(post.authorName)}</span>
      ${statusBadge(post.status)}
      <time>${formatDate(post.updatedAt, { dateOnly: true })}</time>
    </a>`).join('')}</div>`;
}

function pagination(page) {
  const current = Number(page.page || 1);
  const total = Number(page.totalPages || 0);
  if (total <= 1) return '';
  const buttons = [];
  for (let index = 1; index <= total; index += 1) {
    if (index === 1 || index === total || Math.abs(index - current) <= 2) {
      buttons.push(`<button type="button" data-page="${index}" class="${index === current ? 'active' : ''}">${index}</button>`);
    } else if (buttons.at(-1) !== '<span>…</span>') buttons.push('<span>…</span>');
  }
  return `<nav class="pagination" aria-label="게시물 페이지"><button type="button" data-page="${current - 1}" ${current <= 1 ? 'disabled' : ''}>←</button>${buttons.join('')}<button type="button" data-page="${current + 1}" ${current >= total ? 'disabled' : ''}>→</button></nav>`;
}

export async function renderPosts({ api, shell }) {
  const filters = { text: '', category: '', page: 1, size: 8 };
  const [categories] = await Promise.all([api.get('/posts/categories')]);
  shell.setContent(`
    <section class="page-heading"><div><span class="eyebrow">CONTENT</span><h1>게시물</h1><p>검색, 페이지네이션, CRUD API를 한 화면에서 확인하세요.</p></div><a class="primary-button" href="#/posts/new">새 게시물</a></section>
    <section class="panel filter-panel"><form id="post-filter" class="filter-form">
      <label class="search-field"><span>⌕</span><input name="text" type="search" placeholder="제목과 내용 검색"></label>
      <label><span class="sr-only">카테고리</span><select name="category"><option value="">모든 카테고리</option>${(categories || []).map(category => `<option>${escapeHtml(category)}</option>`).join('')}</select></label>
      <button class="secondary-button" type="submit">검색</button>
    </form></section>
    <section class="panel post-panel"><div class="panel-heading"><div><h2>콘텐츠 목록</h2><p id="post-count"></p></div></div><div id="post-results"></div><div id="post-pagination"></div></section>`);

  const form = shell.page.querySelector('#post-filter');
  const results = shell.page.querySelector('#post-results');
  const pager = shell.page.querySelector('#post-pagination');
  const count = shell.page.querySelector('#post-count');
  async function load() {
    results.innerHTML = '<div class="loading-panel compact"><span class="spinner"></span></div>';
    const page = await api.get(`/posts?${queryString(filters)}`);
    count.textContent = `총 ${Number(page.total || 0).toLocaleString()}개`;
    results.innerHTML = postRows(page);
    pager.innerHTML = pagination(page);
  }
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const values = formValues(form);
    filters.text = values.text.trim(); filters.category = values.category; filters.page = 1;
    try { await load(); } catch (error) { shell.showPageError(error); }
  });
  pager.addEventListener('click', async event => {
    const button = event.target.closest('[data-page]');
    if (!button || button.disabled) return;
    filters.page = Number(button.dataset.page);
    try { await load(); } catch (error) { shell.showPageError(error); }
  });
  await load();
}

export async function renderPostEditor({ api, route, router, shell }) {
  const editing = route.name === 'post-detail';
  const [categories, post] = await Promise.all([
    api.get('/posts/categories'),
    editing ? api.get(`/posts/${encodeURIComponent(route.params.id)}`) : Promise.resolve(null),
  ]);
  shell.setContent(`
    <section class="page-heading"><div><a class="back-link" href="#/posts">← 게시물 목록</a><h1>${editing ? '게시물 편집' : '새 게시물'}</h1><p>${editing ? '콘텐츠와 공개 상태를 수정합니다.' : '새로운 콘텐츠를 팀과 공유하세요.'}</p></div>${editing ? '<button class="danger-button" id="delete-post" type="button">게시물 삭제</button>' : ''}</section>
    <section class="panel editor-panel"><form id="post-editor" class="form-stack">
      <label>제목<input name="title" maxlength="200" required value="${escapeHtml(post?.title || '')}" placeholder="게시물 제목"></label>
      <div class="form-grid two"><label>카테고리<input name="category" maxlength="60" list="category-list" value="${escapeHtml(post?.category || '')}" placeholder="공지"></label>
      <datalist id="category-list">${(categories || []).map(category => `<option value="${escapeHtml(category)}"></option>`).join('')}</datalist>
      <label>상태<select name="status"><option value="draft" ${post?.status === 'draft' ? 'selected' : ''}>초안</option><option value="published" ${post?.status === 'published' ? 'selected' : ''}>공개</option><option value="archived" ${post?.status === 'archived' ? 'selected' : ''}>보관</option></select></label></div>
      <label>내용<textarea name="content" rows="14" maxlength="10000" placeholder="내용을 입력하세요.">${escapeHtml(post?.content || '')}</textarea></label>
      <div class="editor-meta">${editing ? `<span>작성자 ${escapeHtml(post.authorName)}</span><span>최근 수정 ${formatDate(post.updatedAt)}</span>` : '<span>저장 후 목록과 Dashboard에 반영됩니다.</span>'}</div>
      <div class="form-actions"><p class="form-error" id="editor-error" hidden></p><a class="secondary-button" href="#/posts">취소</a><button class="primary-button" type="submit">${editing ? '변경사항 저장' : '게시물 만들기'}</button></div>
    </form></section>`);
  const form = shell.page.querySelector('#post-editor');
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    const errorBox = form.querySelector('#editor-error');
    button.disabled = true; errorBox.hidden = true;
    try {
      const payload = formValues(form);
      const saved = editing
        ? await api.put(`/posts/${encodeURIComponent(route.params.id)}`, payload)
        : await api.post('/posts', payload);
      shell.toast(editing ? '게시물을 수정했습니다.' : '게시물을 만들었습니다.');
      router.go(`/posts/${saved.id}`);
    } catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
    finally { button.disabled = false; }
  });
  shell.page.querySelector('#delete-post')?.addEventListener('click', async () => {
    if (!shell.confirm('이 게시물을 완전히 삭제할까요?')) return;
    try {
      await api.delete(`/posts/${encodeURIComponent(route.params.id)}`);
      shell.toast('게시물을 삭제했습니다.'); router.go('/posts');
    } catch (error) { shell.toast(errorText(error), 'error'); }
  });
}
