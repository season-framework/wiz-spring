import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiService } from '../api.service';
import { Member, arrayFrom, displayDate, errorMessage } from '../models';

@Component({
  selector: 'app-members-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <header class="page-header">
      <div><p class="eyebrow">TEAM</p><h1>멤버</h1><p class="muted">함께 일하는 팀원을 초대하고 관리합니다.</p></div>
      <button class="button primary" type="button" (click)="openCreate()">＋ 멤버 초대</button>
    </header>
    @if (notice()) { <div class="alert success">{{ notice() }}</div> }
    @if (error()) { <div class="alert error">{{ error() }}</div> }
    <section class="panel table-panel">
      <div class="panel-heading"><h2>전체 멤버 <span class="count">{{ members().length }}</span></h2><button class="icon-button" type="button" title="새로고침" (click)="load()">↻</button></div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>멤버</th><th>연락처</th><th>권한</th><th>가입일</th><th><span class="sr-only">관리</span></th></tr></thead>
          <tbody>
            @for (member of members(); track member.id) {
              <tr>
                <td><button class="person-button" type="button" (click)="view(member.id)"><span class="avatar">{{ initials(member.name) }}</span><span><strong>{{ member.name }}</strong><small>#{{ member.id }}</small></span></button></td>
                <td><strong>{{ member.email }}</strong><small>{{ member.mobile || '연락처 미등록' }}</small></td>
                <td><span class="badge">{{ roleName(member.role) }}</span></td>
                <td>{{ formatDate(member.createdAt) }}</td>
                <td><button class="icon-button danger" type="button" title="삭제" (click)="remove(member)">×</button></td>
              </tr>
            } @empty {
              <tr><td colspan="5"><div class="empty-state"><span>♙</span><p>{{ loading() ? '멤버를 불러오는 중입니다…' : '등록된 멤버가 없습니다.' }}</p></div></td></tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    @if (createOpen()) {
      <div class="modal-backdrop" (click)="closeCreate()">
        <form class="modal-card" [formGroup]="createForm" (ngSubmit)="create()" (click)="$event.stopPropagation()">
          <div class="modal-heading"><div><p class="eyebrow">NEW MEMBER</p><h2>멤버 초대</h2></div><button class="icon-button" type="button" (click)="closeCreate()">×</button></div>
          <p class="muted">초기 비밀번호는 <code>welcome1</code>입니다.</p>
          <label>이름<input formControlName="name" placeholder="홍길동"></label>
          <label>이메일<input type="email" formControlName="email" placeholder="member@example.com"></label>
          <label>권한<select formControlName="role"><option value="viewer">뷰어</option><option value="editor">에디터</option><option value="user">사용자</option><option value="admin">관리자</option></select></label>
          <div class="modal-actions"><button class="button ghost" type="button" (click)="closeCreate()">취소</button><button class="button primary" type="submit" [disabled]="createForm.invalid || saving()">{{ saving() ? '초대 중…' : '초대하기' }}</button></div>
        </form>
      </div>
    }
    @if (selected()) {
      <div class="modal-backdrop" (click)="selected.set(null)">
        <section class="modal-card profile-preview" (click)="$event.stopPropagation()">
          <button class="icon-button modal-close" type="button" (click)="selected.set(null)">×</button>
          <span class="avatar avatar-large">{{ initials(selected()!.name) }}</span><h2>{{ selected()!.name }}</h2><p>{{ selected()!.email }}</p>
          <dl><div><dt>권한</dt><dd>{{ roleName(selected()!.role) }}</dd></div><div><dt>연락처</dt><dd>{{ selected()!.mobile || '미등록' }}</dd></div><div><dt>가입일</dt><dd>{{ formatDate(selected()!.createdAt) }}</dd></div></dl>
        </section>
      </div>
    }
  `
})
export class MembersPageComponent {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  readonly members = signal<Member[]>([]);
  readonly selected = signal<Member | null>(null);
  readonly createOpen = signal(false);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly formatDate = displayDate;
  readonly createForm = this.fb.nonNullable.group({
    name: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    role: ['viewer', Validators.required]
  });

  constructor() { void this.load(); }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const response = await this.api.get<Member[] | { items?: Member[]; members?: Member[] }>('/members');
      this.members.set(arrayFrom<Member>(response, 'items', 'members'));
    } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.loading.set(false); }
  }

  openCreate(): void { this.notice.set(''); this.createOpen.set(true); }
  closeCreate(): void { this.createOpen.set(false); }

  async create(): Promise<void> {
    if (this.createForm.invalid || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    try {
      const created = await this.api.post<Member>('/members', this.createForm.getRawValue());
      this.members.update(items => [created, ...items]);
      this.createForm.reset({ name: '', email: '', role: 'viewer' });
      this.createOpen.set(false);
      this.notice.set(`${created.name} 멤버를 초대했습니다.`);
    } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.saving.set(false); }
  }

  async view(id: number | string): Promise<void> {
    try { this.selected.set(await this.api.get<Member>(`/members/${id}`)); }
    catch (error) { this.error.set(errorMessage(error)); }
  }

  async remove(member: Member): Promise<void> {
    if (!confirm(`${member.name} 멤버를 삭제할까요?`)) return;
    try {
      await this.api.delete(`/members/${member.id}`);
      this.members.update(items => items.filter(item => item.id !== member.id));
      this.notice.set(`${member.name} 멤버를 삭제했습니다.`);
    } catch (error) { this.error.set(errorMessage(error)); }
  }

  initials(name: string): string { return (name || '?').slice(0, 2).toUpperCase(); }
  roleName(role?: string): string {
    return ({ ADMIN: '관리자', EDITOR: '에디터', USER: '사용자', VIEWER: '뷰어' } as Record<string, string>)[role?.toUpperCase() ?? ''] ?? '뷰어';
  }
}
