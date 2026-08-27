import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiService } from '../api.service';
import { ChatMessage, arrayFrom, displayDate, errorMessage } from '../models';
import { SessionService } from '../session.service';

@Component({
  selector: 'app-chat-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <header class="page-header"><div><p class="eyebrow">LIVE</p><h1>팀 채팅</h1><p class="muted">SSE로 팀의 대화를 실시간으로 받아봅니다.</p></div><span class="connection" [class.online]="connected()"><i></i>{{ connected() ? '실시간 연결됨' : '연결 중…' }}</span></header>
    @if (error()) { <div class="alert error">{{ error() }}</div> }
    <section class="panel chat-panel">
      <div class="chat-heading"><div class="avatar-stack"><span class="avatar">W</span><span class="avatar">S</span><span class="avatar">+</span></div><div><h2>프로젝트 라운지</h2><p>모든 멤버가 참여하는 공개 채널</p></div></div>
      <div class="message-list" id="message-list" aria-live="polite">
        @for (message of messages(); track message.id || $index) {
          <article class="message" [class.mine]="mine(message)">
            <span class="avatar">{{ initials(sender(message)) }}</span>
            <div><p><strong>{{ sender(message) }}</strong><time>{{ formatDate(message.sentAt || message.createdAt) }}</time></p><div class="message-bubble">{{ text(message) }}</div></div>
          </article>
        } @empty { <div class="empty-state"><span>◌</span><h2>아직 메시지가 없습니다.</h2><p>팀에 첫 인사를 건네 보세요.</p></div> }
      </div>
      <form class="message-form" [formGroup]="form" (ngSubmit)="send()">
        <input formControlName="text" maxlength="500" autocomplete="off" placeholder="메시지를 입력하세요…" aria-label="메시지">
        <button class="button primary" type="submit" [disabled]="form.invalid || sending()">{{ sending() ? '…' : '보내기 ↑' }}</button>
      </form>
    </section>
  `
})
export class ChatPageComponent implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly fb = inject(FormBuilder);
  private stream?: EventSource;
  private destroyed = false;
  readonly messages = signal<ChatMessage[]>([]);
  readonly connected = signal(false);
  readonly sending = signal(false);
  readonly error = signal('');
  readonly formatDate = displayDate;
  readonly form = this.fb.nonNullable.group({ text: ['', [Validators.required, Validators.maxLength(500)]] });

  constructor() { void this.initialize(); }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.stream?.close();
  }

  async initialize(): Promise<void> {
    try {
      const response = await this.api.get<ChatMessage[] | { messages?: ChatMessage[]; items?: ChatMessage[] }>('/chat/messages');
      const initial = arrayFrom<ChatMessage>(response, 'messages', 'items');
      this.messages.set(initial);
      this.scrollToEnd();
      const stream = await this.api.eventSource(`/chat/stream?after=${Number(initial.at(-1)?.id ?? 0)}`);
      if (this.destroyed) return stream.close();
      this.stream = stream;
      stream.onopen = () => this.connected.set(true);
      stream.onerror = () => this.connected.set(false);
      stream.addEventListener('chat.message', event => this.receive(event as MessageEvent<string>));
    } catch (error) { this.error.set(errorMessage(error)); }
  }

  async send(): Promise<void> {
    if (this.form.invalid || this.sending()) return;
    const text = this.form.controls.text.value.trim();
    if (!text) return;
    this.sending.set(true); this.error.set('');
    try {
      const message = await this.api.post<ChatMessage>('/chat/messages', { text });
      this.append(message);
      this.form.reset({ text: '' });
      this.scrollToEnd();
    } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.sending.set(false); }
  }

  private receive(event: MessageEvent<string>): void {
    try {
      const message = JSON.parse(event.data) as ChatMessage;
      if (this.append(message)) {
        this.scrollToEnd();
      }
    } catch { this.error.set('실시간 메시지를 해석하지 못했습니다.'); }
  }

  private append(message: ChatMessage): boolean {
    if (this.messages().some(item => item.id !== undefined && item.id === message.id)) return false;
    this.messages.update(items => [...items, message]
      .sort((left, right) => Number(left.id ?? 0) - Number(right.id ?? 0)));
    return true;
  }

  private scrollToEnd(): void {
    setTimeout(() => document.getElementById('message-list')?.scrollTo({ top: 1000000, behavior: 'smooth' }));
  }

  sender(message: ChatMessage): string {
    if (message.authorName) return message.authorName;
    if (typeof message.sender === 'string') return message.sender;
    return message.sender?.name || message.author || '알 수 없음';
  }
  text(message: ChatMessage): string { return message.text ?? message.content ?? message.message ?? ''; }
  initials(name: string): string { return (name || '?').slice(0, 2).toUpperCase(); }
  mine(message: ChatMessage): boolean {
    const user = this.session.user();
    return (message.authorId !== undefined && message.authorId === user?.id) || this.sender(message) === user?.name;
  }
}
