import { Component, inject, signal } from '@angular/core';
import { ApiService } from '../api.service';
import { displayDate, errorMessage } from '../models';

interface DashboardStat {
  key: string;
  label: string;
  value: number | string;
  change?: string;
  icon?: string;
  tone?: string;
}

interface RecentActivity {
  id: number | string;
  title: string;
  category?: string;
  authorName?: string;
  status?: string;
  createdAt?: string;
}

interface DashboardResponse {
  project?: string;
  stats?: DashboardStat[];
  recent?: RecentActivity[];
  recentActivities?: RecentActivity[];
}

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  template: `
    <header class="page-header">
      <div><p class="eyebrow">OVERVIEW</p><h1>안녕하세요 👋</h1><p class="muted">{{ project() }}의 오늘을 한눈에 확인하세요.</p></div>
      <span class="date-chip">{{ today }}</span>
    </header>
    @if (error()) { <div class="alert error">{{ error() }} <button type="button" (click)="load()">다시 시도</button></div> }
    <section class="stats-grid" aria-label="주요 통계">
      @if (loading()) {
        @for (item of [1, 2, 3, 4]; track item) { <div class="stat-card skeleton"></div> }
      } @else {
        @for (stat of stats(); track stat.key) {
          <article class="stat-card" [attr.data-tone]="stat.tone || stat.key">
            <span class="stat-icon">{{ statIcon(stat.icon) }}</span>
            <div><p>{{ stat.label }}</p><strong>{{ stat.value }}</strong><small>{{ stat.change || '최신 데이터' }}</small></div>
          </article>
        }
      }
    </section>
    <section class="panel activity-panel">
      <div class="panel-heading"><div><p class="eyebrow">ACTIVITY</p><h2>최근 활동</h2></div><a class="text-link" href="/posts">전체 게시글 보기 →</a></div>
      <div class="activity-list">
        @for (item of recent(); track item.id) {
          <article class="activity-row">
            <span class="activity-dot"></span>
            <div><strong>{{ item.title }}</strong><p>{{ item.authorName || '알 수 없음' }} · {{ item.category || '일반' }}</p></div>
            <div class="activity-meta"><span class="badge">{{ item.status || '게시' }}</span><time>{{ formatDate(item.createdAt) }}</time></div>
          </article>
        } @empty { <div class="empty-state"><span>◎</span><p>최근 활동이 없습니다.</p></div> }
      </div>
    </section>
  `
})
export class DashboardPageComponent {
  private readonly api = inject(ApiService);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly project = signal('__WIZ_PROJECT_NAME__');
  readonly stats = signal<DashboardStat[]>([]);
  readonly recent = signal<RecentActivity[]>([]);
  readonly today = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'full' }).format(new Date());
  readonly formatDate = displayDate;

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const response = await this.api.get<DashboardResponse>('/dashboard');
      this.project.set(response.project || '__WIZ_PROJECT_NAME__');
      this.stats.set(response.stats ?? []);
      this.recent.set(response.recent ?? response.recentActivities ?? []);
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  statIcon(icon?: string): string {
    return ({ document: '▤', check: '✓', pencil: '✎', users: '♙' } as Record<string, string>)[icon ?? ''] ?? '◆';
  }
}
