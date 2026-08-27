import { useEffect, useState } from 'react';
import { api, displayDate, listFrom, messageOf } from '../api/client.js';
import { initials } from '../layout/AppShell.jsx';
import { Empty } from './DashboardPage.jsx';

export default function MembersPage() {
  const [members, setMembers] = useState([]);
  const [selected, setSelected] = useState(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  async function load() { try { setMembers(listFrom(await api('/members'), 'items', 'members')); } catch (failure) { setError(messageOf(failure)); } }
  useEffect(() => { load(); }, []);
  async function view(id) { try { setSelected(await api(`/members/${id}`)); } catch (failure) { setError(messageOf(failure)); } }
  async function remove(member) { if (!confirm(`${member.name} 멤버를 삭제할까요?`)) return; try { await api(`/members/${member.id}`, { method: 'DELETE' }); setMembers(items => items.filter(item => item.id !== member.id)); setNotice(`${member.name} 멤버를 삭제했습니다.`); } catch (failure) { setError(messageOf(failure)); } }
  return <><header className="page-header"><div><p className="eyebrow">TEAM</p><h1>멤버</h1><p className="muted">함께 일하는 팀원을 초대하고 관리합니다.</p></div><button className="button primary" onClick={() => setCreating(true)}>＋ 멤버 초대</button></header>
    {notice && <div className="alert success">{notice}</div>}{error && <div className="alert error">{error}</div>}
    <section className="panel table-panel"><div className="panel-heading"><h2>전체 멤버 <span className="count">{members.length}</span></h2><button className="icon-button" onClick={load}>↻</button></div><div className="table-wrap"><table><thead><tr><th>멤버</th><th>연락처</th><th>권한</th><th>가입일</th><th><span className="sr-only">관리</span></th></tr></thead><tbody>{members.length ? members.map(member => <tr key={member.id}><td><button className="person-button" onClick={() => view(member.id)}><span className="avatar">{initials(member.name)}</span><span><strong>{member.name}</strong><small>#{member.id}</small></span></button></td><td><strong>{member.email}</strong><small>{member.mobile || '연락처 미등록'}</small></td><td><span className="badge">{roleName(member.role)}</span></td><td>{displayDate(member.createdAt)}</td><td><button className="icon-button danger" onClick={() => remove(member)}>×</button></td></tr>) : <tr><td colSpan="5"><Empty icon="♙" text="등록된 멤버가 없습니다." /></td></tr>}</tbody></table></div></section>
    {creating && <MemberModal onClose={() => setCreating(false)} onCreated={member => { setMembers(items => [member, ...items]); setCreating(false); setNotice(`${member.name} 멤버를 초대했습니다.`); }} onError={failure => setError(messageOf(failure))} />}
    {selected && <div className="modal-backdrop" onClick={() => setSelected(null)}><section className="modal-card profile-preview" onClick={event => event.stopPropagation()}><button className="icon-button modal-close" onClick={() => setSelected(null)}>×</button><span className="avatar avatar-large">{initials(selected.name)}</span><h2>{selected.name}</h2><p>{selected.email}</p><dl><div><dt>권한</dt><dd>{roleName(selected.role)}</dd></div><div><dt>연락처</dt><dd>{selected.mobile || '미등록'}</dd></div><div><dt>가입일</dt><dd>{displayDate(selected.createdAt)}</dd></div></dl></section></div>}
  </>;
}

function MemberModal({ onClose, onCreated, onError }) {
  const [saving, setSaving] = useState(false);
  async function submit(event) { event.preventDefault(); setSaving(true); const form = new FormData(event.currentTarget); try { onCreated(await api('/members', { method: 'POST', body: Object.fromEntries(form) })); } catch (failure) { onError(failure); } finally { setSaving(false); } }
  return <div className="modal-backdrop" onClick={onClose}><form className="modal-card" onSubmit={submit} onClick={event => event.stopPropagation()}><div className="modal-heading"><div><p className="eyebrow">NEW MEMBER</p><h2>멤버 초대</h2></div><button className="icon-button" type="button" onClick={onClose}>×</button></div><p className="muted">초기 비밀번호는 <code>welcome1</code>입니다.</p><label>이름<input name="name" required placeholder="홍길동" /></label><label>이메일<input name="email" type="email" required placeholder="member@example.com" /></label><label>권한<select name="role" defaultValue="viewer"><option value="viewer">뷰어</option><option value="editor">에디터</option><option value="user">사용자</option><option value="admin">관리자</option></select></label><div className="modal-actions"><button className="button ghost" type="button" onClick={onClose}>취소</button><button className="button primary" disabled={saving}>{saving ? '초대 중…' : '초대하기'}</button></div></form></div>;
}

const roleName = role => ({ ADMIN: '관리자', EDITOR: '에디터', USER: '사용자', VIEWER: '뷰어' })[String(role || '').toUpperCase()] || '뷰어';
