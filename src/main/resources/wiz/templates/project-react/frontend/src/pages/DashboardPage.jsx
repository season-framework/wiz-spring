import { useEffect, useState } from 'react';
import { api, displayDate, messageOf } from '../api/client.js';

export default function DashboardPage() {
  const [data, setData] = useState({ project: '__WIZ_PROJECT_NAME__', stats: [], recent: [] });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  async function load() {
    setLoading(true); setError('');
    try { const value = await api('/dashboard'); setData({ project: value.project || '__WIZ_PROJECT_NAME__', stats: value.stats || [], recent: value.recent || value.recentActivities || [] }); }
    catch (failure) { setError(messageOf(failure)); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);
  return <><header className="page-header"><div><p className="eyebrow">OVERVIEW</p><h1>안녕하세요 👋</h1><p className="muted">{data.project}의 오늘을 한눈에 확인하세요.</p></div><span className="date-chip">{new Intl.DateTimeFormat('ko-KR', { dateStyle: 'full' }).format(new Date())}</span></header>
    {error && <div className="alert error">{error}<button onClick={load}>다시 시도</button></div>}
    <section className="stats-grid">{loading ? [1,2,3,4].map(item => <div className="stat-card skeleton" key={item} />) : data.stats.map(stat => <article className="stat-card" key={stat.key}><span className="stat-icon">{statIcon(stat.icon)}</span><div><p>{stat.label}</p><strong>{stat.value}</strong><small>{stat.change || '최신 데이터'}</small></div></article>)}</section>
    <section className="panel activity-panel"><div className="panel-heading"><div><p className="eyebrow">ACTIVITY</p><h2>최근 활동</h2></div><a className="text-link" href="#/posts">전체 게시글 보기 →</a></div><div className="activity-list">{data.recent.length ? data.recent.map(item => <article className="activity-row" key={item.id}><span className="activity-dot" /><div><strong>{item.title}</strong><p>{item.authorName || '알 수 없음'} · {item.category || '일반'}</p></div><div className="activity-meta"><span className="badge">{item.status || '게시'}</span><time>{displayDate(item.createdAt)}</time></div></article>) : <Empty icon="◎" text="최근 활동이 없습니다." />}</div></section>
  </>;
}

export function Empty({ icon, title, text }) { return <div className="empty-state"><span>{icon}</span>{title && <h2>{title}</h2>}<p>{text}</p></div>; }

const statIcon = icon => ({ document: '▤', check: '✓', pencil: '✎', users: '♙' })[icon] || '◆';
