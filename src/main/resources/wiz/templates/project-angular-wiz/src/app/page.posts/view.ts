import { OnInit, signal } from '@angular/core';
import { errorMessage, formatDate, Post, PostPage } from '../../libs/sample';

export class Component implements OnInit {
  public readonly pageData = signal<PostPage>({ items: [], total: 0, page: 1, size: 8, totalPages: 0 });
  public readonly categories = signal<string[]>([]);
  public readonly text = signal('');
  public readonly category = signal('');
  public readonly editingId = signal<string | null>(null);
  public readonly saving = signal(false);
  public readonly error = signal('');
  public readonly form = { title: '', content: '', category: 'General', status: 'draft' };

  public ngOnInit(): void {
    void Promise.all([this.load(1), this.loadCategories()]);
  }

  public format(value: string): string {
    return formatDate(value);
  }

  public search(): void {
    void this.load(1);
  }

  public previous(): void {
    void this.load(Math.max(1, this.pageData().page - 1));
  }

  public next(): void {
    void this.load(this.pageData().page + 1);
  }

  public edit(post: Post): void {
    this.editingId.set(post.id);
    Object.assign(this.form, {
      title: post.title,
      content: post.content,
      category: post.category,
      status: post.status
    });
  }

  public newPost(): void {
    this.editingId.set(null);
    Object.assign(this.form, { title: '', content: '', category: 'General', status: 'draft' });
  }

  public async save(): Promise<void> {
    this.saving.set(true);
    this.error.set('');
    try {
      const id = this.editingId();
      if (id) await wiz.api.put<Post>(`/posts/${id}`, this.form);
      else await wiz.api.post<Post>('/posts', this.form);
      this.newPost();
      await Promise.all([this.load(this.pageData().page), this.loadCategories()]);
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  public async remove(): Promise<void> {
    const id = this.editingId();
    if (!id || !confirm('이 게시물을 삭제할까요?')) return;
    try {
      await wiz.api.delete<void>(`/posts/${id}`);
      this.newPost();
      await this.load(1);
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }

  private async load(page: number): Promise<void> {
    const query = new URLSearchParams({ page: String(page), size: '8' });
    if (this.text().trim()) query.set('text', this.text().trim());
    if (this.category()) query.set('category', this.category());
    try {
      this.pageData.set(await wiz.api.get<PostPage>(`/posts?${query}`));
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }

  private async loadCategories(): Promise<void> {
    try {
      this.categories.set(await wiz.api.get<string[]>('/posts/categories'));
    } catch (error) {
      this.error.set(errorMessage(error));
    }
  }
}
