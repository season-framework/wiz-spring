export interface SessionUser {
  authenticated: boolean;
  id: string | null;
  email: string | null;
  name: string | null;
  role: string | null;
}

export interface Stat {
  key: string;
  label: string;
  value: number;
  change: number;
  icon: string;
  tone: string;
}

export interface RecentPost {
  id: string;
  title: string;
  category: string;
  authorName: string;
  status: string;
  createdAt: string;
}

export interface Dashboard {
  project: string;
  stats: Stat[];
  recent: RecentPost[];
}

export interface Member {
  id: string;
  email: string;
  name: string;
  mobile?: string;
  role: string;
  createdAt: string;
  updatedAt: string;
}

export interface Post {
  id: string;
  title: string;
  content: string;
  summary: string;
  category: string;
  authorId: string;
  authorName: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface PostPage {
  items: Post[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface ChatMessage {
  id: number;
  authorId: string;
  authorName: string;
  text: string;
  sentAt: string;
}

export function errorMessage(error: any): string {
  const fields = error?.data?.fieldErrors;
  if (fields && typeof fields === 'object') {
    const first = Object.values(fields)[0];
    if (first) return String(first);
  }
  return error?.data?.message || error?.message || '요청을 처리하지 못했습니다.';
}

export function formatDate(value?: string): string {
  if (!value) return '-';
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value));
}

export function applyStoredTheme(): boolean {
  const stored = localStorage.getItem('sample-theme');
  const dark = stored === 'dark' || (!stored && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset['theme'] = dark ? 'dark' : 'light';
  return dark;
}

export function setTheme(dark: boolean): void {
  document.documentElement.dataset['theme'] = dark ? 'dark' : 'light';
  localStorage.setItem('sample-theme', dark ? 'dark' : 'light');
}
