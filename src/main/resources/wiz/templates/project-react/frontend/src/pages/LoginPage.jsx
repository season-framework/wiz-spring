import { useState } from 'react';
import { api, messageOf } from '../api/client.js';

export default function LoginPage({ onLogin, initialError = '' }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(initialError);
  const [form, setForm] = useState({ email: 'admin@example.com', password: 'admin1234' });
  async function submit(event) {
    event.preventDefault(); setLoading(true); setError('');
    try { onLogin(await api('/auth/login', { method: 'POST', body: form })); }
    catch (failure) { setError(messageOf(failure)); }
    finally { setLoading(false); }
  }
  return <main className="auth-page">
    <section className="auth-visual"><div className="auth-brand"><span className="brand-mark">W</span> __WIZ_PROJECT_NAME__</div><div><p className="eyebrow">WIZ SPRING STARTER</p><h1>팀의 업무를<br />한곳에서 관리하세요.</h1><p>멤버, 콘텐츠, 대화와 프로필을 갖춘 완성형 샘플입니다.</p></div><small>Spring Boot 4 + React</small></section>
    <section className="auth-panel"><form className="auth-card" onSubmit={submit}><span className="mobile-auth-logo brand-mark">W</span><div><p className="eyebrow">WELCOME BACK</p><h2>로그인</h2><p className="muted">데모 계정으로 바로 둘러볼 수 있습니다.</p></div>{error && <div className="alert error">{error}</div>}<label>이메일<input type="email" value={form.email} autoComplete="username" onChange={event => setForm({ ...form, email: event.target.value })} /></label><label>비밀번호<input type="password" value={form.password} autoComplete="current-password" onChange={event => setForm({ ...form, password: event.target.value })} /></label><button className="button primary wide" disabled={loading}>{loading ? '로그인 중…' : '로그인'}</button><div className="demo-account"><strong>데모 계정</strong><code>admin@example.com</code><code>admin1234</code><button type="button" onClick={() => setForm({ email: 'admin@example.com', password: 'admin1234' })}>입력</button></div></form></section>
  </main>;
}
