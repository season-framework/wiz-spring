import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { errorMessage } from '../models';
import { SessionService } from '../session.service';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <main class="auth-page">
      <section class="auth-visual">
        <div class="auth-brand"><span class="brand-mark">W</span> __WIZ_PROJECT_NAME__</div>
        <div><p class="eyebrow">WIZ SPRING STARTER</p><h1>팀의 업무를<br>한곳에서 관리하세요.</h1><p>멤버, 콘텐츠, 대화와 프로필을 갖춘 완성형 샘플입니다.</p></div>
        <small>Spring Boot 4 + Angular</small>
      </section>
      <section class="auth-panel">
        <form class="auth-card" [formGroup]="form" (ngSubmit)="submit()">
          <span class="mobile-auth-logo brand-mark">W</span>
          <div><p class="eyebrow">WELCOME BACK</p><h2>로그인</h2><p class="muted">데모 계정으로 바로 둘러볼 수 있습니다.</p></div>
          @if (error()) { <div class="alert error" role="alert">{{ error() }}</div> }
          <label>이메일<input type="email" formControlName="email" autocomplete="username"></label>
          <label>비밀번호<input type="password" formControlName="password" autocomplete="current-password"></label>
          <button class="button primary wide" type="submit" [disabled]="form.invalid || loading()">
            {{ loading() ? '로그인 중…' : '로그인' }}
          </button>
          <div class="demo-account"><strong>데모 계정</strong><code>admin@example.com</code><code>admin1234</code><button type="button" (click)="fillDemo()">입력</button></div>
        </form>
      </section>
    </main>
  `
})
export class LoginPageComponent {
  private readonly fb = inject(FormBuilder);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly form = this.fb.nonNullable.group({
    email: ['admin@example.com', [Validators.required, Validators.email]],
    password: ['admin1234', [Validators.required, Validators.minLength(4)]]
  });

  async submit(): Promise<void> {
    if (this.form.invalid || this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    try {
      const { email, password } = this.form.getRawValue();
      await this.session.login(email, password);
      const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/dashboard';
      await this.router.navigateByUrl(returnUrl.startsWith('/') ? returnUrl : '/dashboard');
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  fillDemo(): void {
    this.form.setValue({ email: 'admin@example.com', password: 'admin1234' });
  }
}
