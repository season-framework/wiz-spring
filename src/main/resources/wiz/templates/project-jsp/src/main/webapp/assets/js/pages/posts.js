import { apiClient, pageUrl } from '../api.js';
import { emptyPanel, escapeHtml, formatDate, formValues, showFatal, statusBadge } from '../ui.js';

const filters = { text: '', category: '', page: 1, size: 8 };
function query() { const value = new URLSearchParams(); if (filters.text) value.set('text', filters.text); if (filters.category) value.set('category', filters.category); value.set('page', filters.page); value.set('size', filters.size); return value; }
function rows(page) {
    if (!page.items?.length) return emptyPanel('검색 결과가 없습니다.', '검색어나 카테고리를 바꾸거나 새 게시물을 작성해 보세요.');
    return `<div class="post-list">${page.items.map(post => `<a class="post-row" href="${pageUrl(`/posts/${encodeURIComponent(post.id)}`)}"><span class="post-title"><strong>${escapeHtml(post.title)}</strong><small>${escapeHtml(post.summary || '내용 미리보기가 없습니다.')}</small></span><span class="category-chip">${escapeHtml(post.category || '일반')}</span><span class="post-author">${escapeHtml(post.authorName)}</span>${statusBadge(post.status)}<time>${formatDate(post.updatedAt, true)}</time></a>`).join('')}</div>`;
}
function pagination(page) {
    const current = Number(page.page || 1), total = Number(page.totalPages || 0); if (total <= 1) return '';
    const buttons = []; for (let index = 1; index <= total; index += 1) { if (index === 1 || index === total || Math.abs(index - current) <= 2) buttons.push(`<button type="button" data-page="${index}" class="${index === current ? 'active' : ''}">${index}</button>`); else if (buttons.at(-1) !== '<span>…</span>') buttons.push('<span>…</span>'); }
    return `<nav class="pagination"><button type="button" data-page="${current - 1}" ${current <= 1 ? 'disabled' : ''}>←</button>${buttons.join('')}<button type="button" data-page="${current + 1}" ${current >= total ? 'disabled' : ''}>→</button></nav>`;
}

try {
    const api = await apiClient();
    const categories = await api.get('/posts/categories');
    document.querySelector('#category-filter').insertAdjacentHTML('beforeend', categories.map(category => `<option>${escapeHtml(category)}</option>`).join(''));
    const form = document.querySelector('#post-filter'), results = document.querySelector('#post-results'), pager = document.querySelector('#post-pagination'), count = document.querySelector('#post-count');
    async function load() { results.innerHTML = '<div class="loading-panel"><span class="spinner"></span></div>'; const page = await api.get(`/posts?${query()}`); count.textContent = `총 ${Number(page.total || 0).toLocaleString()}개`; results.innerHTML = rows(page); pager.innerHTML = pagination(page); }
    form.addEventListener('submit', async event => { event.preventDefault(); const values = formValues(form); filters.text = values.text.trim(); filters.category = values.category; filters.page = 1; try { await load(); } catch (error) { showFatal(error); } });
    pager.addEventListener('click', async event => { const button = event.target.closest('[data-page]'); if (!button || button.disabled) return; filters.page = Number(button.dataset.page); try { await load(); } catch (error) { showFatal(error); } });
    await load();
} catch (error) {
    showFatal(error);
}
