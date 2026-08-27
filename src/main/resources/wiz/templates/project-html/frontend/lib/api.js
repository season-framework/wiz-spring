export class ApiError extends Error {
  constructor(message, { status = 0, data = null } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.data = data;
    this.fieldErrors = data?.fieldErrors ?? {};
  }
}

export function normalizeApiPrefix(value) {
  const prefix = String(value || '/api').trim();
  const withSlash = prefix.startsWith('/') ? prefix : `/${prefix}`;
  return withSlash === '/' ? '' : withSlash.replace(/\/+$/, '');
}

export function joinApiPath(prefix, resource) {
  const normalized = String(resource || '').trim();
  const path = normalized.startsWith('/') ? normalized : `/${normalized}`;
  return `${normalizeApiPrefix(prefix)}${path}`.replace(/\/{2,}/g, '/');
}

export async function loadRuntimeConfiguration(fetchImpl = globalThis.fetch) {
  const response = await fetchImpl('/app-config.json', {
    cache: 'no-store',
    credentials: 'same-origin',
    headers: { accept: 'application/json' },
  });
  if (!response.ok) {
    throw new ApiError(`런타임 설정을 불러오지 못했습니다. (HTTP ${response.status})`, {
      status: response.status,
    });
  }
  return response.json();
}

async function responseBody(response) {
  if (response.status === 204) return undefined;
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json') || contentType.includes('+json')) {
    return response.json();
  }
  return response.text();
}

export class ApiClient {
  constructor(prefix, fetchImpl = globalThis.fetch) {
    this.prefix = normalizeApiPrefix(prefix);
    this.fetch = fetchImpl;
  }

  url(resource) {
    return joinApiPath(this.prefix, resource);
  }

  async request(method, resource, body) {
    const response = await this.fetch(this.url(resource), {
      method,
      credentials: 'same-origin',
      headers: body === undefined
        ? { accept: 'application/json' }
        : { accept: 'application/json', 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = await responseBody(response);
    if (!response.ok) {
      const message = data?.message || data?.detail || `요청에 실패했습니다. (HTTP ${response.status})`;
      throw new ApiError(message, { status: response.status, data });
    }
    return data;
  }

  get(resource) { return this.request('GET', resource); }
  post(resource, body) { return this.request('POST', resource, body); }
  put(resource, body) { return this.request('PUT', resource, body); }
  delete(resource) { return this.request('DELETE', resource); }

  eventSource(resource) {
    return new EventSource(this.url(resource), { withCredentials: true });
  }
}
