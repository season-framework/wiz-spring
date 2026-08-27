import { computed, OnInit, signal } from '@angular/core';
import { errorMessage, formatDate, Member } from '../../libs/sample';

export class Component implements OnInit {
  public readonly members = signal<Member[]>([]);
  public readonly query = signal('');
  public readonly inviteOpen = signal(false);
  public readonly saving = signal(false);
  public readonly error = signal('');
  public readonly notice = signal('');
  public readonly form = { name: '', email: '', role: 'viewer' };
  public readonly filteredMembers = computed(() => {
    const query = this.query().trim().toLowerCase();
    if (!query) return this.members();
    return this.members().filter((member) => `${member.name} ${member.email}`.toLowerCase().includes(query));
  });

  public ngOnInit(): void {
    void this.load();
  }

  public format(value: string): string {
    return formatDate(value);
  }

  public async load(): Promise<void> {
    try {
      this.members.set(await wiz.api.get<Member[]>('/members'));
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }

  public async invite(): Promise<void> {
    this.saving.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const member = await wiz.api.post<Member>('/members', this.form);
      this.members.update((items) => [...items, member]);
      Object.assign(this.form, { name: '', email: '', role: 'viewer' });
      this.inviteOpen.set(false);
      this.notice.set(`${member.name} 님을 초대했습니다. 초기 비밀번호는 welcome1입니다.`);
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  public async remove(member: Member): Promise<void> {
    if (!confirm(`${member.name} 님을 워크스페이스에서 제거할까요?`)) return;
    try {
      await wiz.api.delete<void>(`/members/${member.id}`);
      this.members.update((items) => items.filter((item) => item.id !== member.id));
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }
}
