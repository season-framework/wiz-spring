export interface WizRuntimeConfig {
  apiPrefix?: string;
  [key: string]: unknown;
}

export interface WizContext {
  appId: string;
  namespace: string;
}

function apiPrefix(): string {
  const configured = window.__APP_CONFIG__?.apiPrefix?.trim() || '/api';
  const leadingSlash = configured.startsWith('/') ? configured : `/${configured}`;
  return leadingSlash === '/' ? '' : leadingSlash.replace(/\/+$/, '');
}

function apiPath(resource: string): string {
  const normalized = String(resource || '').trim();
  const withSlash = normalized.startsWith('/') ? normalized : `/${normalized}`;
  return `${apiPrefix()}${withSlash}`.replace(/\/{2,}/g, '/');
}

async function responseBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined;
  const contentType = response.headers.get('content-type') || '';
  return contentType.includes('application/json') ? response.json() : response.text();
}

class WizApi {
  public url(resource: string): string {
    return apiPath(resource);
  }

  public eventSource(resource: string): EventSource {
    return new EventSource(this.url(resource), { withCredentials: true });
  }

  public async request<T>(method: string, resource: string, body?: unknown): Promise<T> {
    const response = await fetch(apiPath(resource), {
      method,
      credentials: 'same-origin',
      headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const data = await responseBody(response);
    if (!response.ok) {
      const error = new Error(`API ${method} ${resource} failed with HTTP ${response.status}`);
      Object.assign(error, { status: response.status, data });
      throw error;
    }
    return data as T;
  }

  public get<T>(resource: string): Promise<T> {
    return this.request<T>('GET', resource);
  }

  public post<T>(resource: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', resource, body);
  }

  public put<T>(resource: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', resource, body);
  }

  public patch<T>(resource: string, body?: unknown): Promise<T> {
    return this.request<T>('PATCH', resource, body);
  }

  public delete<T>(resource: string): Promise<T> {
    return this.request<T>('DELETE', resource);
  }
}

export async function loadRuntimeConfig(): Promise<WizRuntimeConfig> {
  const response = await fetch('/app-config.json', { cache: 'no-store', credentials: 'same-origin' });
  if (!response.ok) throw new Error(`Runtime config returned HTTP ${response.status}`);
  const config = await response.json() as WizRuntimeConfig;
  window.__APP_CONFIG__ = config;
  return config;
}

export default class Wiz {
  public readonly api = new WizApi();

  constructor(public readonly context: WizContext) {}
}
