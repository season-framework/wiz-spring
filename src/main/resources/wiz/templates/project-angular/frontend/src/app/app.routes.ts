import { Routes } from '@angular/router';
import { AppShellComponent } from './layout/app-shell.component';
import { LoginPageComponent } from './pages/login-page.component';
import { DashboardPageComponent } from './pages/dashboard-page.component';
import { MembersPageComponent } from './pages/members-page.component';
import { PostsPageComponent } from './pages/posts-page.component';
import { ProfilePageComponent } from './pages/profile-page.component';
import { ChatPageComponent } from './pages/chat-page.component';
import { authGuard } from './auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginPageComponent, title: '로그인 · __WIZ_PROJECT_NAME__' },
  {
    path: '',
    component: AppShellComponent,
    canActivate: [authGuard],
    children: [
      { path: 'dashboard', component: DashboardPageComponent, title: '대시보드 · __WIZ_PROJECT_NAME__' },
      { path: 'members', component: MembersPageComponent, title: '멤버 · __WIZ_PROJECT_NAME__' },
      { path: 'posts', component: PostsPageComponent, title: '게시글 · __WIZ_PROJECT_NAME__' },
      { path: 'profile', component: ProfilePageComponent, title: '내 프로필 · __WIZ_PROJECT_NAME__' },
      { path: 'chat', component: ChatPageComponent, title: '채팅 · __WIZ_PROJECT_NAME__' },
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' }
    ]
  },
  { path: '**', redirectTo: '' }
];
