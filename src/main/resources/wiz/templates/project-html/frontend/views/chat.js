import { emptyPanel, errorText, escapeHtml, formatDate, formValues } from '../lib/ui.js';

function messageMarkup(message, ownId) {
  const own = message.authorId === ownId;
  return `<article class="chat-message ${own ? 'own' : ''}" data-message-id="${message.id}">
    <span class="avatar soft">${escapeHtml((message.authorName || '?').charAt(0))}</span>
    <div><p><strong>${escapeHtml(message.authorName)}</strong><time>${formatDate(message.sentAt)}</time></p><div class="bubble">${escapeHtml(message.text)}</div></div>
  </article>`;
}

export async function renderChat({ api, shell, store }) {
  const messages = await api.get('/chat/messages');
  shell.setContent(`
    <section class="page-heading"><div><span class="eyebrow">LIVE</span><h1>실시간 채팅</h1><p>Spring MVC의 Server-Sent Events 스트림을 확인하세요.</p></div><span class="connection-badge" id="connection-state"><i></i>연결 중</span></section>
    <section class="panel chat-panel"><div class="chat-feed" id="chat-feed">${(messages || []).length ? messages.map(message => messageMarkup(message, store.session.id)).join('') : emptyPanel('대화를 시작해 보세요.', '메시지는 접속 중인 모든 브라우저에 실시간으로 전달됩니다.')}</div>
      <form class="chat-compose" id="chat-form"><label><span class="sr-only">메시지</span><input name="text" maxlength="500" autocomplete="off" placeholder="메시지를 입력하세요" required></label><button class="primary-button" type="submit">전송</button></form>
      <p class="form-error chat-error" id="chat-error" hidden></p>
    </section>`);
  const feed = shell.page.querySelector('#chat-feed');
  const form = shell.page.querySelector('#chat-form');
  const errorBox = shell.page.querySelector('#chat-error');
  const connection = shell.page.querySelector('#connection-state');
  const knownIds = new Set((messages || []).map(message => String(message.id)));
  const append = message => {
    if (!message || knownIds.has(String(message.id))) return;
    if (!knownIds.size) feed.innerHTML = '';
    knownIds.add(String(message.id));
    feed.insertAdjacentHTML('beforeend', messageMarkup(message, store.session.id));
    [...feed.querySelectorAll('[data-message-id]')]
      .sort((left, right) => Number(left.dataset.messageId) - Number(right.dataset.messageId))
      .forEach(element => feed.append(element));
    feed.scrollTop = feed.scrollHeight;
  };
  feed.scrollTop = feed.scrollHeight;
  const events = api.eventSource(`/chat/stream?after=${encodeURIComponent(messages.at(-1)?.id ?? 0)}`);
  events.onopen = () => { connection.classList.add('connected'); connection.innerHTML = '<i></i>실시간 연결됨'; };
  events.onerror = () => { connection.classList.remove('connected'); connection.innerHTML = '<i></i>재연결 중'; };
  events.addEventListener('chat.message', event => {
    try { append(JSON.parse(event.data)); }
    catch { shell.toast('실시간 메시지를 해석하지 못했습니다.', 'error'); }
  });
  form.addEventListener('submit', async event => {
    event.preventDefault(); const values = formValues(form); const button = form.querySelector('button'); button.disabled = true; errorBox.hidden = true;
    try {
      const sent = await api.post('/chat/messages', { text: values.text.trim() });
      append(sent); form.reset(); form.elements.text.focus();
    } catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
    finally { button.disabled = false; }
  });
  return () => events.close();
}
