import { signal } from '@angular/core';
import { Router } from '@angular/router';
import { errorMessage, SessionUser } from '../../libs/sample';

export class Component {
  public readonly form = { email: 'admin@example.com', password: 'admin1234' };
  public readonly loading = signal(false);
  public readonly error = signal('');

  constructor(private readonly router: Router) {
    void this.redirectAuthenticatedUser();
  }

  public useDemo(): void {
    this.form.email = 'admin@example.com';
    this.form.password = 'admin1234';
  }

  public async login(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      await wiz.api.post<SessionUser>('/auth/login', this.form);
      await this.router.navigateByUrl('/dashboard');
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async redirectAuthenticatedUser(): Promise<void> {
    try {
      const session = await wiz.api.get<SessionUser>('/auth/session');
      if (session.authenticated) await this.router.navigateByUrl('/dashboard');
    } catch {
      // The login form is the expected fallback for an anonymous session.
    }
  }
}
