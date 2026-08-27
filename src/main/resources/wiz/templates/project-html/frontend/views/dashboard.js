import { emptyPanel, escapeHtml, formatDate, statusBadge } from '../lib/ui.js';

export async function renderDashboard({ api, shell, store }) {
  const dashboard = await api.get('/dashboard');
  const stats = Array.isArray(dashboard.stats) ? dashboard.stats : [];
  const recent = Array.isArray(dashboard.recent) ? dashboard.recent : [];
  shell.setContent(`
    <section class="page-heading">
      <div><span class="eyebrow">OVERVIEW</span><h1>안녕하세요, ${escapeHtml(store.session.name)}님.</h1><p>${escapeHtml(dashboard.project)}의 지금 상태를 한눈에 확인하세요.</p></div>
      <a class="primary-button" href="#/posts/new">새 게시물</a>
    </section>
    <section class="stat-grid">
      ${stats.map(stat => `
        <article class="stat-card tone-${escapeHtml(stat.tone || 'blue')}">
          <div class="stat-icon">${escapeHtml(stat.icon || '◇')}</div>
          <div><p>${escapeHtml(stat.label)}</p><strong>${Number(stat.value || 0).toLocaleString()}</strong>
          <small class="${Number(stat.change) >= 0 ? 'positive' : 'negative'}">${Number(stat.change) >= 0 ? '↑' : '↓'} ${Math.abs(Number(stat.change || 0))}%</small></div>
        </article>`).join('')}
    </section>
    <section class="dashboard-grid">
      <article class="panel span-two">
        <div class="panel-heading"><div><h2>최근 게시물</h2><p>최근 생성된 콘텐츠와 공개 상태입니다.</p></div><a href="#/posts">전체 보기 →</a></div>
        ${recent.length ? `<div class="activity-list">${recent.map(post => `
          <a class="activity-item" href="#/posts/${encodeURIComponent(post.id)}">
            <span class="avatar soft">${escapeHtml((post.authorName || '?').charAt(0))}</span>
            <span class="activity-copy"><strong>${escapeHtml(post.title)}</strong><small>${escapeHtml(post.authorName)} · ${formatDate(post.createdAt)}</small></span>
            <span class="category-chip">${escapeHtml(post.category || '일반')}</span>${statusBadge(post.status)}
          </a>`).join('')}</div>` : emptyPanel('아직 게시물이 없습니다.', '첫 게시물을 작성해 최근 활동을 채워보세요.')}
      </article>
      <aside class="panel quick-panel">
        <div class="panel-heading"><div><h2>빠른 시작</h2><p>샘플의 주요 기능</p></div></div>
        <a class="quick-link" href="#/members"><span class="quick-icon purple">◎</span><span><strong>팀 멤버 관리</strong><small>초대와 역할 확인</small></span><b>→</b></a>
        <a class="quick-link" href="#/chat"><span class="quick-icon green">◇</span><span><strong>실시간 채팅</strong><small>SSE 연결 확인</small></span><b>→</b></a>
        <a class="quick-link" href="/swagger-ui" target="_blank" rel="noreferrer"><span class="quick-icon blue">{ }</span><span><strong>Swagger UI</strong><small>API 계약 살펴보기</small></span><b>↗</b></a>
      </aside>
    </section>`);
}
