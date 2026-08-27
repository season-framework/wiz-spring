import { apiClient } from '../api.js';
import { emptyPanel, errorText, escapeHtml, formatDate, formValues, showFatal, toast } from '../ui.js';

function messageMarkup(message, ownId) {
    const own = message.authorId === ownId;
    return `<article class="chat-message ${own ? 'own' : ''}" data-message-id="${message.id}"><span class="avatar soft">${escapeHtml((message.authorName || '?').charAt(0))}</span><div><p><strong>${escapeHtml(message.authorName)}</strong><time>${formatDate(message.sentAt)}</time></p><div class="bubble">${escapeHtml(message.text)}</div></div></article>`;
}

let source;
try {
    const api = await apiClient();
    const [session, messages] = await Promise.all([api.get('/auth/session'), api.get('/chat/messages')]);
    const feed = document.querySelector('#chat-feed'), form = document.querySelector('#chat-form'), errorBox = document.querySelector('#chat-error'), connection = document.querySelector('#connection-state');
    feed.innerHTML = messages.length ? messages.map(message => messageMarkup(message, session.id)).join('') : emptyPanel('대화를 시작해 보세요.', '접속 중인 모든 브라우저에 실시간으로 전달됩니다.');
    const ids = new Set(messages.map(message => String(message.id)));
    const append = message => { if (!message || ids.has(String(message.id))) return; if (!ids.size) feed.innerHTML = ''; ids.add(String(message.id)); feed.insertAdjacentHTML('beforeend', messageMarkup(message, session.id)); [...feed.querySelectorAll('[data-message-id]')].sort((left, right) => Number(left.dataset.messageId) - Number(right.dataset.messageId)).forEach(element => feed.append(element)); feed.scrollTop = feed.scrollHeight; };
    feed.scrollTop = feed.scrollHeight;
    source = api.eventSource(`/chat/stream?after=${encodeURIComponent(messages.at(-1)?.id ?? 0)}`);
    source.onopen = () => { connection.classList.add('connected'); connection.innerHTML = '<i></i>실시간 연결됨'; };
    source.onerror = () => { connection.classList.remove('connected'); connection.innerHTML = '<i></i>재연결 중'; };
    source.addEventListener('chat.message', event => { try { append(JSON.parse(event.data)); } catch { toast('실시간 메시지를 해석하지 못했습니다.', 'error'); } });
    form.addEventListener('submit', async event => {
        event.preventDefault(); const values = formValues(form); const button = form.querySelector('button'); button.disabled = true; errorBox.hidden = true;
        try { const sent = await api.post('/chat/messages', { text: values.text.trim() }); append(sent); form.reset(); form.elements.text.focus(); }
        catch (error) { errorBox.textContent = errorText(error); errorBox.hidden = false; }
        finally { button.disabled = false; }
    });
} catch (error) {
    showFatal(error);
}
addEventListener('pagehide', () => source?.close(), { once: true });
