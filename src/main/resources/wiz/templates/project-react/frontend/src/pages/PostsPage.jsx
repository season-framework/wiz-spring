import { useEffect, useState } from 'react';
import { api, displayDate, listFrom, messageOf } from '../api/client.js';
import { initials } from '../layout/AppShell.jsx';
import { Empty } from './DashboardPage.jsx';

export default function PostsPage() {
  const [posts, setPosts] = useState([]);
  const [categories, setCategories] = useState([]);
  const [filters, setFilters] = useState({ text: '', category: '' });
  const [paging, setPaging] = useState({ page: 1, size: 9, total: 0, totalPages: 0 });
  const [detail, setDetail] = useState(null);
  const [editing, setEditing] = useState(undefined);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);

  async function load(page = paging.page, nextFilters = filters) {
    setLoading(true); setError('');
    try {
      const result = await api('/posts', { query: { ...nextFilters, page, size: paging.size } });
      setPosts(result.items || []);
      setPaging(current => ({ ...current, page: result.page ?? page, size: result.size ?? current.size, total: result.total ?? 0, totalPages: result.totalPages ?? 0 }));
    } catch (failure) { setError(messageOf(failure)); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(1); api('/posts/categories').then(value => setCategories(listFrom(value, 'categories'))).catch(failure => setError(messageOf(failure))); }, []);
  async function openDetail(id) { try { setDetail(await api(`/posts/${id}`)); } catch (failure) { setError(messageOf(failure)); } }
  async function remove(post) { if (!confirm(`“${post.title}” 게시글을 삭제할까요?`)) return; try { await api(`/posts/${post.id}`, { method: 'DELETE' }); setDetail(null); setNotice('게시글을 삭제했습니다.'); await load(); } catch (failure) { setError(messageOf(failure)); } }
  return <><header className="page-header"><div><p className="eyebrow">CONTENTS</p><h1>게시글</h1><p className="muted">팀의 소식과 지식을 한곳에 기록합니다.</p></div><button className="button primary" onClick={() => setEditing(null)}>＋ 새 게시글</button></header>
    <form className="filter-bar" onSubmit={event => { event.preventDefault(); load(1); }}><label className="search-field"><span>⌕</span><input value={filters.text} placeholder="제목과 내용을 검색하세요" onChange={event => setFilters({ ...filters, text: event.target.value })} /></label><select aria-label="카테고리" value={filters.category} onChange={event => setFilters({ ...filters, category: event.target.value })}><option value="">모든 카테고리</option>{categories.map(category => <option key={category}>{category}</option>)}</select><button className="button secondary">검색</button></form>
    {notice && <div className="alert success">{notice}</div>}{error && <div className="alert error">{error}</div>}
    <section className="posts-grid">{posts.length ? posts.map(post => <article className="post-card" key={post.id}><div className="post-top"><span className="badge accent">{post.category || '일반'}</span><span className="badge">{statusName(post.status)}</span></div><button className="post-body" onClick={() => openDetail(post.id)}><h2>{post.title}</h2><p>{post.summary || excerpt(post.content)}</p></button><footer><span className="avatar avatar-small">{initials(post.authorName || '알 수 없음')}</span><span><strong>{post.authorName || '알 수 없음'}</strong><small>{displayDate(post.createdAt)}</small></span><span className="card-actions"><button className="icon-button" title="수정" onClick={() => setEditing(post)}>✎</button><button className="icon-button danger" title="삭제" onClick={() => remove(post)}>×</button></span></footer></article>) : <div className="panel posts-empty"><Empty icon="▤" title={loading ? '게시글을 불러오는 중입니다…' : '검색 결과가 없습니다.'} text="새 게시글을 작성해 첫 기록을 남겨 보세요." /></div>}</section>
    <nav className="pagination"><button className="button ghost" disabled={paging.page <= 1} onClick={() => load(paging.page - 1)}>← 이전</button><span><strong>{paging.total ? paging.page : 0}</strong> / {paging.totalPages} · 총 {paging.total}개</span><button className="button ghost" disabled={paging.page >= paging.totalPages} onClick={() => load(paging.page + 1)}>다음 →</button></nav>
    {detail && <div className="modal-backdrop" onClick={() => setDetail(null)}><article className="modal-card post-detail" onClick={event => event.stopPropagation()}><button className="icon-button modal-close" onClick={() => setDetail(null)}>×</button><div className="post-top"><span className="badge accent">{detail.category || '일반'}</span><span className="badge">{statusName(detail.status)}</span></div><h2>{detail.title}</h2><p className="post-byline">{detail.authorName || '알 수 없음'} · {displayDate(detail.createdAt)}</p><div className="post-content">{detail.content}</div><div className="modal-actions"><button className="button secondary" onClick={() => { setEditing(detail); setDetail(null); }}>수정</button><button className="button danger-button" onClick={() => remove(detail)}>삭제</button></div></article></div>}
    {editing !== undefined && <PostEditor post={editing} categories={categories} onClose={() => setEditing(undefined)} onSaved={async isNew => { setEditing(undefined); setNotice(isNew ? '게시글을 작성했습니다.' : '게시글을 수정했습니다.'); await load(); }} onError={failure => setError(messageOf(failure))} />}
  </>;
}

function PostEditor({ post, categories, onClose, onSaved, onError }) {
  const [saving, setSaving] = useState(false);
  async function submit(event) { event.preventDefault(); setSaving(true); const body = Object.fromEntries(new FormData(event.currentTarget)); try { await api(post ? `/posts/${post.id}` : '/posts', { method: post ? 'PUT' : 'POST', body }); await onSaved(!post); } catch (failure) { onError(failure); } finally { setSaving(false); } }
  return <div className="modal-backdrop" onClick={onClose}><form className="modal-card editor-card" onSubmit={submit} onClick={event => event.stopPropagation()}><div className="modal-heading"><div><p className="eyebrow">{post ? 'EDIT POST' : 'NEW POST'}</p><h2>{post ? '게시글 수정' : '새 게시글'}</h2></div><button className="icon-button" type="button" onClick={onClose}>×</button></div><label>제목<input name="title" required maxLength="200" defaultValue={post?.title || ''} placeholder="게시글 제목" /></label><div className="form-row"><label>카테고리<select name="category" defaultValue={post?.category || categories[0] || 'GENERAL'}>{categories.length ? categories.map(category => <option key={category}>{category}</option>) : <option value="GENERAL">GENERAL</option>}</select></label><label>상태<select name="status" defaultValue={post?.status?.toLowerCase() || 'published'}><option value="published">게시</option><option value="draft">임시 저장</option></select></label></div><label>내용<textarea name="content" rows="11" defaultValue={post?.content || ''} placeholder="팀과 나눌 내용을 작성하세요" /></label><div className="modal-actions"><button className="button ghost" type="button" onClick={onClose}>취소</button><button className="button primary" disabled={saving}>{saving ? '저장 중…' : '저장'}</button></div></form></div>;
}

const excerpt = content => String(content || '내용이 없습니다.').replace(/\s+/g, ' ').slice(0, 130);
const statusName = status => String(status || '').toUpperCase() === 'DRAFT' ? '임시 저장' : String(status || '').toUpperCase() === 'ARCHIVED' ? '보관' : '게시';
