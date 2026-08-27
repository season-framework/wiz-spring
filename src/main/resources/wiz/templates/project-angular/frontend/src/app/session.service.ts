import { Injectable, inject, signal } from '@angular/core';
import { ApiError, ApiService } from './api.service';
import { SessionResponse, UserProfile } from './models';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly api = inject(ApiService);
  private initialized = false;
  readonly user = signal<UserProfile | null>(null);

  async ensure(): Promise<UserProfile | null> {
    if (this.initialized) return this.user();
    return this.refresh();
  }

  async refresh(): Promise<UserProfile | null> {
    try {
      const response = await this.api.get<SessionResponse>('/auth/session');
      const user = this.normalize(response);
      this.user.set(response.authenticated === false ? null : user);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
      this.user.set(null);
    } finally {
      this.initialized = true;
    }
    return this.user();
  }

  async login(email: string, password: string): Promise<UserProfile> {
    const response = await this.api.post<SessionResponse>('/auth/login', { email, password });
    this.initialized = true;
    const user = this.normalize(response) ?? await this.refresh();
    if (!user) throw new Error('로그인 응답에 사용자 정보가 없습니다.');
    this.user.set(user);
    return user;
  }

  async logout(): Promise<void> {
    await this.api.post<void>('/auth/logout');
    this.initialized = true;
    this.user.set(null);
  }

  updateUser(user: UserProfile): void {
    this.initialized = true;
    this.user.set(user);
  }

  private normalize(value: SessionResponse): UserProfile | null {
    const nested = value.user ?? value.profile;
    if (nested?.email) return nested;
    if (value.email) return {
      id: value.id,
      name: value.name ?? value.email.split('@')[0],
      email: value.email,
      role: value.role
    };
    return null;
  }
}
