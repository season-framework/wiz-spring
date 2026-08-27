import { OnDestroy, OnInit, signal } from '@angular/core';
import { ChatMessage, errorMessage, formatDate, SessionUser } from '../../libs/sample';

export class Component implements OnInit, OnDestroy {
  public readonly messages = signal<ChatMessage[]>([]);
  public readonly currentUserId = signal<string | null>(null);
  public readonly connected = signal(false);
  public readonly sending = signal(false);
  public readonly error = signal('');
  public draft = '';
  private source?: EventSource;

  public ngOnInit(): void {
    void this.initialize();
  }

  public ngOnDestroy(): void {
    this.source?.close();
  }

  public format(value: string): string {
    return formatDate(value);
  }

  public async send(): Promise<void> {
    const text = this.draft.trim();
    if (!text) return;
    this.sending.set(true);
    try {
      const message = await wiz.api.post<ChatMessage>('/chat/messages', { text });
      this.append(message);
      this.draft = '';
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.sending.set(false);
    }
  }

  private async initialize(): Promise<void> {
    try {
      const [messages, session] = await Promise.all([
        wiz.api.get<ChatMessage[]>('/chat/messages'),
        wiz.api.get<SessionUser>('/auth/session')
      ]);
      this.messages.set(messages);
      this.currentUserId.set(session.id);
      this.connect(messages.at(-1)?.id ?? 0);
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }

  private connect(afterId: number): void {
    this.source = wiz.api.eventSource(`/chat/stream?after=${afterId}`);
    this.source.onopen = () => this.connected.set(true);
    this.source.onerror = () => this.connected.set(false);
    this.source.addEventListener('chat.message', (event) => {
      this.append(JSON.parse((event as MessageEvent).data) as ChatMessage);
    });
  }

  private append(message: ChatMessage): void {
    this.messages.update((items) => items.some((item) => item.id === message.id)
      ? items
      : [...items, message].sort((left, right) => left.id - right.id));
  }
}
