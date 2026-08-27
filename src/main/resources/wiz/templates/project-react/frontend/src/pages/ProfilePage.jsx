import { useEffect, useState } from 'react';
import { api, displayDate, messageOf } from '../api/client.js';
import { initials } from '../layout/AppShell.jsx';

export default function ProfilePage({ user, onUserChange }) {
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  useEffect(() => { api('/profile').then(setProfile).catch(failure => setError(messageOf(failure))); }, []);
  async function saveProfile(event) { event.preventDefault(); setSaving(true); setError(''); setNotice(''); try { const updated = await api('/profile', { method: 'PUT', body: Object.fromEntries(new FormData(event.currentTarget)) }); setProfile(updated); onUserChange({ ...user, id: updated.id, name: updated.name, email: updated.email, role: updated.role }); setNotice('프로필을 저장했습니다.'); } catch (failure) { setError(messageOf(failure)); } finally { setSaving(false); } }
  async function changePassword(event) { event.preventDefault(); setSaving(true); setError(''); setNotice(''); const form = event.currentTarget; try { await api('/profile/password', { method: 'PUT', body: Object.fromEntries(new FormData(form)) }); form.reset(); setNotice('비밀번호를 변경했습니다.'); } catch (failure) { setError(messageOf(failure)); } finally { setSaving(false); } }
  return <><header className="page-header"><div><p className="eyebrow">ACCOUNT</p><h1>내 프로필</h1><p className="muted">개인 정보와 계정 보안을 관리합니다.</p></div></header>{notice && <div className="alert success">{notice}</div>}{error && <div className="alert error">{error}</div>}
    <div className="profile-layout"><aside className="panel profile-summary"><span className="avatar avatar-xl">{initials(profile?.name)}</span><h2>{profile?.name || '불러오는 중…'}</h2><p>{profile?.email}</p><span className="badge accent">{profile?.role?.toUpperCase() === 'ADMIN' ? '관리자' : '멤버'}</span><dl><div><dt>멤버 번호</dt><dd>#{profile?.id || '—'}</dd></div><div><dt>가입일</dt><dd>{displayDate(profile?.createdAt)}</dd></div></dl></aside>
      <div className="profile-forms"><form className="panel form-panel" key={`profile-${profile?.id}`} onSubmit={saveProfile}><div className="panel-heading"><div><p className="eyebrow">PROFILE</p><h2>기본 정보</h2></div></div><label>이메일<input value={profile?.email || ''} disabled /><small>이메일은 변경할 수 없습니다.</small></label><label>이름<input name="name" required defaultValue={profile?.name || ''} /></label><label>휴대전화<input name="mobile" defaultValue={profile?.mobile || ''} placeholder="010-0000-0000" /></label><div className="form-actions"><button className="button primary" disabled={saving}>변경사항 저장</button></div></form>
        <form className="panel form-panel" onSubmit={changePassword}><div className="panel-heading"><div><p className="eyebrow">SECURITY</p><h2>비밀번호 변경</h2></div></div><div className="form-row"><label>현재 비밀번호<input type="password" name="currentPassword" required autoComplete="current-password" /></label><label>새 비밀번호<input type="password" name="newPassword" required minLength="8" autoComplete="new-password" /><small>8자 이상 입력하세요.</small></label></div><div className="form-actions"><button className="button secondary" disabled={saving}>비밀번호 변경</button></div></form></div>
    </div></>;
}
