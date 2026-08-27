import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from '../session.service';

interface NavigationItem {
  path: string;
  label: string;
  icon: string;
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="app-frame">
      <button class="mobile-menu icon-button" type="button" aria-label="메뉴 열기" (click)="menuOpen.set(true)">☰</button>
      <div class="sidebar-backdrop" [class.visible]="menuOpen()" (click)="menuOpen.set(false)"></div>
      <aside class="sidebar" [class.open]="menuOpen()">
        <div class="brand"><span class="brand-mark">W</span><span>__WIZ_PROJECT_NAME__</span></div>
        <nav aria-label="주 메뉴">
          @for (item of navigation; track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active" (click)="menuOpen.set(false)">
              <span aria-hidden="true">{{ item.icon }}</span><span>{{ item.label }}</span>
            </a>
          }
        </nav>
        <div class="sidebar-footer">
          <button class="theme-button" type="button" (click)="toggleTheme()">
            <span>{{ theme() === 'dark' ? '☀' : '☾' }}</span>
            {{ theme() === 'dark' ? '라이트 모드' : '다크 모드' }}
          </button>
          <div class="user-card">
            <span class="avatar">{{ initials() }}</span>
            <span class="user-copy"><strong>{{ session.user()?.name }}</strong><small>{{ session.user()?.email }}</small></span>
            <button class="icon-button" type="button" aria-label="로그아웃" title="로그아웃" (click)="logout()">↪</button>
          </div>
        </div>
      </aside>
      <main class="content"><router-outlet /></main>
    </div>
  `
})
export class AppShellComponent {
  readonly session = inject(SessionService);
  private readonly router = inject(Router);
  readonly menuOpen = signal(false);
  readonly theme = signal<'light' | 'dark'>(this.initialTheme());
  readonly initials = computed(() => (this.session.user()?.name || 'W').slice(0, 2).toUpperCase());
  readonly navigation: NavigationItem[] = [
    { path: '/dashboard', label: '대시보드', icon: '▦' },
    { path: '/members', label: '멤버', icon: '♙' },
    { path: '/posts', label: '게시글', icon: '▤' },
    { path: '/chat', label: '팀 채팅', icon: '◌' },
    { path: '/profile', label: '내 프로필', icon: '⚙' }
  ];

  constructor() {
    document.documentElement.dataset['theme'] = this.theme();
  }

  toggleTheme(): void {
    const next = this.theme() === 'dark' ? 'light' : 'dark';
    this.theme.set(next);
    document.documentElement.dataset['theme'] = next;
    localStorage.setItem('wiz-theme', next);
  }

  async logout(): Promise<void> {
    try {
      await this.session.logout();
    } finally {
      await this.router.navigateByUrl('/login');
    }
  }

  private initialTheme(): 'light' | 'dark' {
    const saved = localStorage.getItem('wiz-theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
}
