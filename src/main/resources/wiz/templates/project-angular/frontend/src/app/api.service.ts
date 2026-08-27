import { Injectable } from '@angular/core';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly payload: unknown = null
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export type QueryValue = string | number | boolean | null | undefined;

@Injectable({ providedIn: 'root' })
export class ApiService {
  private prefix?: Promise<string>;

  get<T>(path: string, query?: Record<string, QueryValue>): Promise<T> {
    return this.request<T>('GET', path, undefined, query);
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body);
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body);
  }

  delete<T = void>(path: string): Promise<T> {
    return this.request<T>('DELETE', path);
  }

  async eventSource(path: string): Promise<EventSource> {
    return new EventSource(await this.url(path), { withCredentials: true });
  }

  async url(path: string, query?: Record<string, QueryValue>): Promise<string> {
    const prefix = await this.apiPrefix();
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    const url = new URL(`${prefix}${normalizedPath}`, window.location.origin);
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value));
      }
    }
    return `${url.pathname}${url.search}`;
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    query?: Record<string, QueryValue>
  ): Promise<T> {
    const response = await fetch(await this.url(path, query), {
      method,
      credentials: 'include',
      headers: body === undefined ? { Accept: 'application/json' } : {
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const contentType = response.headers.get('content-type') ?? '';
    const payload: unknown = response.status === 204
      ? null
      : contentType.includes('json')
        ? await response.json()
        : await response.text();
    if (!response.ok) {
      const details = payload as { detail?: string; message?: string } | null;
      throw new ApiError(
        details?.detail ?? details?.message ?? `API request failed (${response.status})`,
        response.status,
        payload
      );
    }
    return payload as T;
  }

  private apiPrefix(): Promise<string> {
    return this.prefix ??= fetch('/app-config.json', { credentials: 'same-origin' })
      .then(response => {
        if (!response.ok) throw new Error('애플리케이션 설정을 불러오지 못했습니다.');
        return response.json() as Promise<{ apiPrefix?: string }>;
      })
      .then(configuration => {
        const value = configuration.apiPrefix?.trim() || '/api';
        return `/${value.replace(/^\/+|\/+$/g, '')}`;
      });
  }
}
