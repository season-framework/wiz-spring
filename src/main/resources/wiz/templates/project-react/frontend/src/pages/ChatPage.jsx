import { useEffect, useRef, useState } from 'react';
import { api, apiUrl, displayDate, listFrom, messageOf } from '../api/client.js';
import { initials } from '../layout/AppShell.jsx';
import { Empty } from './DashboardPage.jsx';

function mergeMessage(items, message) {
  if (items.some(item => item.id === message.id)) return items;
  return [...items, message].sort((left, right) => Number(left.id ?? 0) - Number(right.id ?? 0));
}

export default function ChatPage({ user }) {
  const [messages, setMessages] = useState([]);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const listRef = useRef(null);
  const scroll = () => requestAnimationFrame(() => listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' }));
  useEffect(() => {
    let source;
    let active = true;
    (async () => {
      try {
        const initial = listFrom(await api('/chat/messages'), 'messages', 'items');
        if (!active) return;
        setMessages(initial);
        scroll();
        const url = await apiUrl('/chat/stream', { after: initial.at(-1)?.id ?? 0 });
        if (!active) return;
        source = new EventSource(url, { withCredentials: true });
        source.onopen = () => setConnected(true);
        source.onerror = () => setConnected(false);
        source.addEventListener('chat.message', event => {
          try { setMessages(items => mergeMessage(items, JSON.parse(event.data))); scroll(); }
          catch { setError('실시간 메시지를 해석하지 못했습니다.'); }
        });
      } catch (failure) {
        if (active) setError(messageOf(failure));
      }
    })();
    return () => { active = false; source?.close(); };
  }, []);
  async function send(event) { event.preventDefault(); const form = event.currentTarget; const data = new FormData(form); const text = String(data.get('text') || '').trim(); if (!text) return; setSending(true); setError(''); try { const message = await api('/chat/messages', { method: 'POST', body: { text } }); setMessages(items => mergeMessage(items, message)); form.reset(); scroll(); } catch (failure) { setError(messageOf(failure)); } finally { setSending(false); } }
  const sender = message => message.authorName || (typeof message.sender === 'string' ? message.sender : message.sender?.name) || message.author || '알 수 없음';
  const mine = message => (message.authorId != null && message.authorId === user.id) || sender(message) === user.name;
  return <><header className="page-header"><div><p className="eyebrow">LIVE</p><h1>팀 채팅</h1><p className="muted">SSE로 팀의 대화를 실시간으로 받아봅니다.</p></div><span className={`connection ${connected ? 'online' : ''}`}><i />{connected ? '실시간 연결됨' : '연결 중…'}</span></header>{error && <div className="alert error">{error}</div>}
    <section className="panel chat-panel"><div className="chat-heading"><div className="avatar-stack"><span className="avatar">W</span><span className="avatar">S</span><span className="avatar">+</span></div><div><h2>프로젝트 라운지</h2><p>모든 멤버가 참여하는 공개 채널</p></div></div><div className="message-list" ref={listRef} aria-live="polite">{messages.length ? messages.map((message, index) => <article className={`message ${mine(message) ? 'mine' : ''}`} key={message.id ?? index}><span className="avatar">{initials(sender(message))}</span><div><p><strong>{sender(message)}</strong><time>{displayDate(message.sentAt || message.createdAt)}</time></p><div className="message-bubble">{message.text ?? message.content ?? message.message ?? ''}</div></div></article>) : <Empty icon="◌" title="아직 메시지가 없습니다." text="팀에 첫 인사를 건네 보세요." />}</div><form className="message-form" onSubmit={send}><input name="text" maxLength="500" autoComplete="off" required placeholder="메시지를 입력하세요…" aria-label="메시지" /><button className="button primary" disabled={sending}>{sending ? '…' : '보내기 ↑'}</button></form></section>
  </>;
}
