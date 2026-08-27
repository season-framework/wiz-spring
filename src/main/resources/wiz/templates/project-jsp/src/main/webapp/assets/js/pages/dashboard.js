import { apiClient, pageUrl } from '../api.js';
import { emptyPanel, escapeHtml, formatDate, showFatal, statusBadge } from '../ui.js';

try {
    const api = await apiClient();
    const dashboard = await api.get('/dashboard');
    const stats = Array.isArray(dashboard.stats) ? dashboard.stats : [];
    const recent = Array.isArray(dashboard.recent) ? dashboard.recent : [];
    document.querySelector('#dashboard-stats').innerHTML = stats.map(stat => `
        <article class="stat-card tone-${escapeHtml(stat.tone || 'blue')}"><div class="stat-icon">${escapeHtml(stat.icon || '◇')}</div><div><p>${escapeHtml(stat.label)}</p><strong>${Number(stat.value || 0).toLocaleString()}</strong><small class="${Number(stat.change) >= 0 ? 'positive' : 'negative'}">${Number(stat.change) >= 0 ? '↑' : '↓'} ${Math.abs(Number(stat.change || 0))}%</small></div></article>`).join('');
    document.querySelector('#dashboard-recent').innerHTML = recent.length ? `<div class="activity-list">${recent.map(post => `
        <a class="activity-item" href="${pageUrl(`/posts/${encodeURIComponent(post.id)}`)}"><span class="avatar soft">${escapeHtml((post.authorName || '?').charAt(0))}</span><span class="activity-copy"><strong>${escapeHtml(post.title)}</strong><small>${escapeHtml(post.authorName)} · ${formatDate(post.createdAt)}</small></span><span class="category-chip">${escapeHtml(post.category || '일반')}</span>${statusBadge(post.status)}</a>`).join('')}</div>` : emptyPanel('아직 게시물이 없습니다.', '첫 게시물을 작성해 최근 활동을 채워보세요.');
} catch (error) {
    showFatal(error);
}
