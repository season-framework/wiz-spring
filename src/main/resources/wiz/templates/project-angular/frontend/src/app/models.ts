export type EntityId = number | string;

export interface UserProfile {
  id?: EntityId;
  name: string;
  email: string;
  role?: string;
  bio?: string;
  avatarUrl?: string;
  joinedAt?: string;
}

export interface SessionResponse {
  authenticated?: boolean;
  id?: EntityId;
  user?: UserProfile;
  profile?: UserProfile;
  name?: string;
  email?: string;
  role?: string;
}

export interface Member {
  id: EntityId;
  name: string;
  email: string;
  role?: string;
  mobile?: string;
  createdAt?: string;
}

export interface Post {
  id: EntityId;
  title: string;
  content: string;
  summary?: string;
  category?: string;
  status?: string;
  authorId?: EntityId;
  authorName?: string;
  author?: string | { name?: string };
  createdAt?: string;
  updatedAt?: string;
}

export interface PostPage {
  items: Post[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface ChatMessage {
  id?: EntityId;
  content?: string;
  message?: string;
  text?: string;
  authorId?: EntityId;
  authorName?: string;
  sender?: string | { name?: string };
  author?: string;
  createdAt?: string;
  sentAt?: string;
}

export function arrayFrom<T>(value: unknown, ...keys: string[]): T[] {
  if (Array.isArray(value)) return value as T[];
  if (!value || typeof value !== 'object') return [];
  const record = value as Record<string, unknown>;
  for (const key of keys) {
    if (Array.isArray(record[key])) return record[key] as T[];
  }
  return [];
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.';
}

export function displayDate(value?: string): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(date);
}
