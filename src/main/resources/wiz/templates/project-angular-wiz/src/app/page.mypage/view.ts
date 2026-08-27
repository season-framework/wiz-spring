import { OnInit, signal } from '@angular/core';
import { errorMessage, Member } from '../../libs/sample';

export class Component implements OnInit {
  public readonly profile = signal<Member | null>(null);
  public readonly saving = signal(false);
  public readonly changingPassword = signal(false);
  public readonly error = signal('');
  public readonly notice = signal('');
  public readonly profileForm = { name: '', mobile: '' };
  public readonly passwordForm = { currentPassword: '', newPassword: '' };

  public ngOnInit(): void {
    void this.load();
  }

  public async saveProfile(): Promise<void> {
    this.saving.set(true);
    this.resetMessages();
    try {
      const profile = await wiz.api.put<Member>('/profile', this.profileForm);
      this.profile.set(profile);
      this.notice.set('프로필을 저장했습니다.');
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  public async changePassword(): Promise<void> {
    this.changingPassword.set(true);
    this.resetMessages();
    try {
      await wiz.api.put<void>('/profile/password', this.passwordForm);
      Object.assign(this.passwordForm, { currentPassword: '', newPassword: '' });
      this.notice.set('비밀번호를 변경했습니다. 다음 로그인부터 새 비밀번호를 사용하세요.');
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.changingPassword.set(false);
    }
  }

  private async load(): Promise<void> {
    try {
      const profile = await wiz.api.get<Member>('/profile');
      this.profile.set(profile);
      Object.assign(this.profileForm, { name: profile.name, mobile: profile.mobile || '' });
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }

  private resetMessages(): void {
    this.error.set('');
    this.notice.set('');
  }
}
