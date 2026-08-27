import { OnInit, signal } from '@angular/core';
import { Dashboard, errorMessage, formatDate, SessionUser } from '../../libs/sample';

export class Component implements OnInit {
  public readonly loading = signal(false);
  public readonly dashboard = signal<Dashboard | null>(null);
  public readonly userName = signal('there');
  public readonly error = signal('');

  public ngOnInit(): void {
    void this.load();
  }

  public format(value: string): string {
    return formatDate(value);
  }

  public async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [dashboard, session] = await Promise.all([
        wiz.api.get<Dashboard>('/dashboard'),
        wiz.api.get<SessionUser>('/auth/session')
      ]);
      this.dashboard.set(dashboard);
      this.userName.set(session.name?.split(' ')[0] || 'there');
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }
}
