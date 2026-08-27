let configuration;

export class ApiError extends Error {
  constructor(message, status, payload) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.payload = payload;
  }
}

async function apiPrefix() {
  configuration ??= fetch('/app-config.json', { credentials: 'same-origin' })
    .then(response => {
      if (!response.ok) throw new Error('애플리케이션 설정을 불러오지 못했습니다.');
      return response.json();
    })
    .then(value => `/${String(value.apiPrefix || '/api').replace(/^\/+|\/+$/g, '')}`);
  return configuration;
}

export async function apiUrl(path, query = {}) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  const url = new URL(`${await apiPrefix()}${normalizedPath}`, window.location.origin);
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value));
  });
  return `${url.pathname}${url.search}`;
}

export async function api(path, options = {}) {
  const { query, body, ...requestOptions } = options;
  const response = await fetch(await apiUrl(path, query), {
    credentials: 'include',
    ...requestOptions,
    headers: body === undefined
      ? { Accept: 'application/json', ...requestOptions.headers }
      : { Accept: 'application/json', 'Content-Type': 'application/json', ...requestOptions.headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const type = response.headers.get('content-type') || '';
  const payload = response.status === 204 ? null : type.includes('json') ? await response.json() : await response.text();
  if (!response.ok) {
    throw new ApiError(payload?.message || payload?.detail || `API request failed (${response.status})`, response.status, payload);
  }
  return payload;
}

export function listFrom(value, ...keys) {
  if (Array.isArray(value)) return value;
  for (const key of keys) if (Array.isArray(value?.[key])) return value[key];
  return [];
}

export function displayDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export function messageOf(error) {
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.';
}
