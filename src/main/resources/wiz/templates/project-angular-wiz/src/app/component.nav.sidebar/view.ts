import { OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { applyStoredTheme, SessionUser, setTheme } from '../../libs/sample';

export class Component implements OnInit {
  public readonly user = signal<SessionUser | null>(null);
  public readonly dark = signal(false);

  constructor(private readonly router: Router) {}

  public ngOnInit(): void {
    this.dark.set(applyStoredTheme());
    void this.loadSession();
  }

  public toggleTheme(): void {
    this.dark.update((value) => !value);
    setTheme(this.dark());
  }

  public async logout(): Promise<void> {
    await wiz.api.post<void>('/auth/logout');
    await this.router.navigateByUrl('/access');
  }

  private async loadSession(): Promise<void> {
    try {
      const current = await wiz.api.get<SessionUser>('/auth/session');
      if (!current.authenticated) throw new Error('Unauthenticated');
      this.user.set(current);
    } catch {
      await this.router.navigateByUrl('/access');
    }
  }
}
