import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiService } from '../api.service';
import { Post, PostPage, arrayFrom, displayDate, errorMessage } from '../models';

@Component({
  selector: 'app-posts-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <header class="page-header">
      <div><p class="eyebrow">CONTENTS</p><h1>게시글</h1><p class="muted">팀의 소식과 지식을 한곳에 기록합니다.</p></div>
      <button class="button primary" type="button" (click)="openCreate()">＋ 새 게시글</button>
    </header>
    <form class="filter-bar" [formGroup]="searchForm" (ngSubmit)="search()">
      <label class="search-field"><span>⌕</span><input formControlName="text" placeholder="제목과 내용을 검색하세요"></label>
      <select formControlName="category" aria-label="카테고리"><option value="">모든 카테고리</option>@for (category of categories(); track category) { <option [value]="category">{{ category }}</option> }</select>
      <button class="button secondary" type="submit">검색</button>
    </form>
    @if (notice()) { <div class="alert success">{{ notice() }}</div> }
    @if (error()) { <div class="alert error">{{ error() }}</div> }
    <section class="posts-grid">
      @for (post of posts(); track post.id) {
        <article class="post-card">
          <div class="post-top"><span class="badge accent">{{ post.category || '일반' }}</span><span class="badge">{{ statusName(post.status) }}</span></div>
          <button class="post-body" type="button" (click)="openDetail(post.id)">
            <h2>{{ post.title }}</h2><p>{{ post.summary || excerpt(post.content) }}</p>
          </button>
          <footer><span class="avatar avatar-small">{{ initials(post.authorName || author(post)) }}</span><span><strong>{{ post.authorName || author(post) }}</strong><small>{{ formatDate(post.createdAt) }}</small></span><span class="card-actions"><button class="icon-button" type="button" title="수정" (click)="edit(post); $event.stopPropagation()">✎</button><button class="icon-button danger" type="button" title="삭제" (click)="remove(post); $event.stopPropagation()">×</button></span></footer>
        </article>
      } @empty {
        <div class="panel empty-state posts-empty"><span>▤</span><h2>{{ loading() ? '게시글을 불러오는 중입니다…' : '검색 결과가 없습니다.' }}</h2><p>새 게시글을 작성해 첫 기록을 남겨 보세요.</p></div>
      }
    </section>
    <nav class="pagination" aria-label="게시글 페이지">
      <button class="button ghost" type="button" [disabled]="page() <= 1" (click)="go(page() - 1)">← 이전</button>
      <span><strong>{{ total() ? page() : 0 }}</strong> / {{ totalPages() }} · 총 {{ total() }}개</span>
      <button class="button ghost" type="button" [disabled]="page() >= totalPages()" (click)="go(page() + 1)">다음 →</button>
    </nav>

    @if (detail()) {
      <div class="modal-backdrop" (click)="detail.set(null)">
        <article class="modal-card post-detail" (click)="$event.stopPropagation()">
          <button class="icon-button modal-close" type="button" (click)="detail.set(null)">×</button>
          <div class="post-top"><span class="badge accent">{{ detail()!.category || '일반' }}</span><span class="badge">{{ statusName(detail()!.status) }}</span></div>
          <h2>{{ detail()!.title }}</h2><p class="post-byline">{{ detail()!.authorName || author(detail()!) }} · {{ formatDate(detail()!.createdAt) }}</p>
          <div class="post-content">{{ detail()!.content }}</div>
          <div class="modal-actions"><button class="button secondary" type="button" (click)="edit(detail()!)">수정</button><button class="button danger-button" type="button" (click)="remove(detail()!)">삭제</button></div>
        </article>
      </div>
    }
    @if (editorOpen()) {
      <div class="modal-backdrop" (click)="closeEditor()">
        <form class="modal-card editor-card" [formGroup]="editorForm" (ngSubmit)="save()" (click)="$event.stopPropagation()">
          <div class="modal-heading"><div><p class="eyebrow">{{ editingId() === null ? 'NEW POST' : 'EDIT POST' }}</p><h2>{{ editingId() === null ? '새 게시글' : '게시글 수정' }}</h2></div><button class="icon-button" type="button" (click)="closeEditor()">×</button></div>
          <label>제목<input formControlName="title" placeholder="게시글 제목"></label>
          <div class="form-row"><label>카테고리<select formControlName="category">@for (category of categories(); track category) { <option [value]="category">{{ category }}</option> }</select></label><label>상태<select formControlName="status"><option value="published">게시</option><option value="draft">임시 저장</option></select></label></div>
          <label>내용<textarea formControlName="content" rows="11" placeholder="팀과 나눌 내용을 작성하세요"></textarea></label>
          <div class="modal-actions"><button class="button ghost" type="button" (click)="closeEditor()">취소</button><button class="button primary" type="submit" [disabled]="editorForm.invalid || saving()">{{ saving() ? '저장 중…' : '저장' }}</button></div>
        </form>
      </div>
    }
  `
})
export class PostsPageComponent {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  readonly posts = signal<Post[]>([]);
  readonly categories = signal<string[]>([]);
  readonly page = signal(1);
  readonly size = 9;
  readonly total = signal(0);
  readonly totalPages = signal(0);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly detail = signal<Post | null>(null);
  readonly editorOpen = signal(false);
  readonly editingId = signal<number | string | null>(null);
  readonly formatDate = displayDate;
  readonly searchForm = this.fb.nonNullable.group({ text: [''], category: [''] });
  readonly editorForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    category: ['GENERAL'],
    status: ['published'],
    content: ['']
  });

  constructor() { void Promise.all([this.loadCategories(), this.load()]); }

  async loadCategories(): Promise<void> {
    try {
      const response = await this.api.get<string[] | { categories?: string[] }>('/posts/categories');
      const categories = arrayFrom<string>(response, 'categories');
      this.categories.set(categories);
      if (categories.length && this.editorForm.controls.category.value === 'GENERAL') {
        this.editorForm.controls.category.setValue(categories[0] ?? 'GENERAL');
      }
    } catch (error) { this.error.set(errorMessage(error)); }
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    const filters = this.searchForm.getRawValue();
    try {
      const result = await this.api.get<PostPage>('/posts', {
        text: filters.text.trim(), category: filters.category, page: this.page(), size: this.size
      });
      this.posts.set(result.items ?? []);
      this.total.set(result.total ?? result.items?.length ?? 0);
      this.totalPages.set(result.totalPages ?? Math.ceil(this.total() / (result.size || this.size)));
    } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.loading.set(false); }
  }

  search(): void { this.page.set(1); void this.load(); }
  go(page: number): void { this.page.set(page); void this.load(); }

  openCreate(): void {
    this.editingId.set(null);
    this.editorForm.reset({ title: '', category: this.categories()[0] ?? 'GENERAL', status: 'published', content: '' });
    this.detail.set(null);
    this.editorOpen.set(true);
  }

  async openDetail(id: number | string): Promise<void> {
    try { this.detail.set(await this.api.get<Post>(`/posts/${id}`)); }
    catch (error) { this.error.set(errorMessage(error)); }
  }

  edit(post: Post): void {
    this.editingId.set(post.id);
    this.editorForm.setValue({ title: post.title, category: post.category ?? this.categories()[0] ?? 'GENERAL', status: post.status?.toLowerCase() ?? 'published', content: post.content ?? '' });
    this.detail.set(null);
    this.editorOpen.set(true);
  }

  closeEditor(): void { this.editorOpen.set(false); }

  async save(): Promise<void> {
    if (this.editorForm.invalid || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    try {
      const id = this.editingId();
      if (id === null) await this.api.post<Post>('/posts', this.editorForm.getRawValue());
      else await this.api.put<Post>(`/posts/${id}`, this.editorForm.getRawValue());
      this.editorOpen.set(false);
      this.notice.set(id === null ? '게시글을 작성했습니다.' : '게시글을 수정했습니다.');
      await this.load();
    } catch (error) { this.error.set(errorMessage(error)); }
    finally { this.saving.set(false); }
  }

  async remove(post: Post): Promise<void> {
    if (!confirm(`“${post.title}” 게시글을 삭제할까요?`)) return;
    try {
      await this.api.delete(`/posts/${post.id}`);
      this.detail.set(null);
      this.notice.set('게시글을 삭제했습니다.');
      await this.load();
    } catch (error) { this.error.set(errorMessage(error)); }
  }

  excerpt(content?: string): string { return content?.replace(/\s+/g, ' ').slice(0, 130) || '내용이 없습니다.'; }
  author(post: Post): string { return typeof post.author === 'string' ? post.author : post.author?.name || '알 수 없음'; }
  initials(name: string): string { return (name || '?').slice(0, 2).toUpperCase(); }
  statusName(status?: string): string { return status?.toUpperCase() === 'DRAFT' ? '임시 저장' : status?.toUpperCase() === 'ARCHIVED' ? '보관' : '게시'; }
}
