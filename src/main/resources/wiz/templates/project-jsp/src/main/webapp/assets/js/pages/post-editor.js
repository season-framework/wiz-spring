import { apiClient, pageUrl } from '../api.js';
import { errorText, escapeHtml, formatDate, formValues, showFatal, toast } from '../ui.js';

const segments = location.pathname.split('/').filter(Boolean);
const postsIndex = segments.lastIndexOf('posts');
const rawId = postsIndex >= 0 ? segments[postsIndex + 1] : null;
const editing = Boolean(rawId && rawId !== 'new');
const id = editing ? decodeURIComponent(rawId) : null;

try {
    const api = await apiClient();
    const [categories, post] = await Promise.all([
        api.get('/posts/categories'),
        editing ? api.get(`/posts/${encodeURIComponent(id)}`) : Promise.resolve(null),
    ]);
    const form = document.querySelector('#post-editor');
    document.querySelector('#category-list').innerHTML = categories.map(category => `<option value="${escapeHtml(category)}"></option>`).join('');
    document.querySelector('#editor-title').textContent = editing ? '게시물 편집' : '새 게시물';
    document.querySelector('#editor-description').textContent = editing ? '콘텐츠와 공개 상태를 수정합니다.' : '새로운 콘텐츠를 팀과 공유하세요.';
    if (post) {
        form.elements.title.value = post.title;
        form.elements.category.value = post.category;
        form.elements.status.value = post.status;
        form.elements.content.value = post.content;
        document.querySelector('#editor-meta').textContent = `작성자 ${post.authorName} · 최근 수정 ${formatDate(post.updatedAt)}`;
        document.querySelector('#delete-post').hidden = false;
    }
    form.addEventListener('submit', async event => {
        event.preventDefault(); const button = form.querySelector('button[type="submit"]'); const errorBox = document.querySelector('#editor-error'); button.disabled = true; errorBox.hidden = true;
        try {
            const payload = formValues(form);
            const saved = editing ? await api.put(`/posts/${encodeURIComponent(id)}`, payload) : await api.post('/posts', payload);
            toast(editing ? '게시물을 수정했습니다.' : '게시물을 만들었습니다.');
            location.assign(pageUrl(`/posts/${encodeURIComponent(saved.id)}`));
        } catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; button.disabled = false; }
    });
    document.querySelector('#delete-post').addEventListener('click', async event => {
        if (!confirm('이 게시물을 완전히 삭제할까요?')) return;
        event.currentTarget.disabled = true;
        try { await api.delete(`/posts/${encodeURIComponent(id)}`); location.assign(pageUrl('/posts')); }
        catch (error) { toast(errorText(error), 'error'); event.currentTarget.disabled = false; }
    });
} catch (error) {
    showFatal(error);
}
