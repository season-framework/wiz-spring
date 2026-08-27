import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiService } from '../api.service';
import { Member, UserProfile, displayDate, errorMessage } from '../models';
import { SessionService } from '../session.service';

@Component({
  selector: 'app-profile-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <header class="page-header"><div><p class="eyebrow">ACCOUNT</p><h1>내 프로필</h1><p class="muted">개인 정보와 계정 보안을 관리합니다.</p></div></header>
    @if (notice()) { <div class="alert success">{{ notice() }}</div> }
    @if (error()) { <div class="alert error">{{ error() }}</div> }
    <div class="profile-layout">
      <aside class="panel profile-summary">
        <span class="avatar avatar-xl">{{ initials(profile()?.name) }}</span><h2>{{ profile()?.name || '불러오는 중…' }}</h2><p>{{ profile()?.email }}</p><span class="badge accent">{{ profile()?.role?.toUpperCase() === 'ADMIN' ? '관리자' : '멤버' }}</span>
        <dl><div><dt>멤버 번호</dt><dd>#{{ profile()?.id || '—' }}</dd></div><div><dt>가입일</dt><dd>{{ formatDate(profile()?.createdAt) }}</dd></div></dl>
      </aside>
      <div class="profile-forms">
        <form class="panel form-panel" [formGroup]="profileForm" (ngSubmit)="saveProfile()">
          <div class="panel-heading"><div><p class="eyebrow">PROFILE</p><h2>기본 정보</h2></div></div>
          <label>이메일<input [value]="profile()?.email || ''" disabled><small>이메일은 변경할 수 없습니다.</small></label>
          <label>이름<input formControlName="name" autocomplete="name"></label>
          <label>휴대전화<input formControlName="mobile" autocomplete="tel" placeholder="010-0000-0000"></label>
          <div class="form-actions"><button class="button primary" type="submit" [disabled]="profileForm.invalid || saving()">변경사항 저장</button></div>
        </form>
        <form class="panel form-panel" [formGroup]="passwordForm" (ngSubmit)="changePassword()">
          <div class="panel-heading"><div><p class="eyebrow">SECURITY</p><h2>비밀번호 변경</h2></div></div>
          <div class="form-row"><label>현재 비밀번호<input type="password" formControlName="currentPassword" autocomplete="current-password"></label><label>새 비밀번호<input type="password" formControlName="newPassword" autocomplete="new-password"><small>8자 이상 입력하세요.</small></label></div>
          <div class="form-actions"><button class="button secondary" type="submit" [disabled]="passwordForm.invalid || savingPassword()">비밀번호 변경</button></div>
        </form>
      </div>
    </div>
  `
})
export class ProfilePageComponent {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly fb = inject(FormBuilder);
  readonly profile = signal<Member | null>(null);
  readonly error = signal('');
  readonly notice = signal('');
  readonly saving = signal(false);
  readonly savingPassword = signal(false);
  readonly formatDate = displayDate;
  readonly profileForm = this.fb.nonNullable.group({ name: ['', Validators.required], mobile: [''] });
  readonly passwordForm = this.fb.nonNullable.group({ currentPassword: ['', Validators.required], newPassword: ['', [Validators.required, Validators.minLength(8)]] });

  constructor() { void this.load(); }

  async load(): Promise<void> {
    try {
      const profile = await this.api.get<Member>('/profile');
      this.profile.set(profile);
      this.profileForm.setValue({ name: profile.name ?? '', mobile: profile.mobile ?? '' });
    } catch (error) { this.error.set(errorMessage(error)); }
  }

  async saveProfile(): Promise<void> {
    if (this.profileForm.invalid || this.saving()) return;
    this.saving.set(true); this.error.set(''); this.notice.set('');
    try {
      const profile = await this.api.put<Member>('/profile', this.profileForm.getRawValue());
      this.profile.set(profile);
      const user: UserProfile = { ...this.session.user(), id: profile.id, name: profile.name, email: profile.email, role: profile.role };
      this.session.updateUser(user);
      this.notice.set('프로필을 저장했습니다.');
    } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.saving.set(false); }
  }

  async changePassword(): Promise<void> {
    if (this.passwordForm.invalid || this.savingPassword()) return;
    this.savingPassword.set(true); this.error.set(''); this.notice.set('');
    try {
      await this.api.put<void>('/profile/password', this.passwordForm.getRawValue());
      this.passwordForm.reset({ currentPassword: '', newPassword: '' });
      this.notice.set('비밀번호를 변경했습니다.');
    } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.savingPassword.set(false); }
  }

  initials(name?: string): string { return (name || '?').slice(0, 2).toUpperCase(); }
}
