import { Routes } from '@angular/router';
import { adminGuard } from './features/admin/admin.guard';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login-page.component').then((m) => m.LoginPageComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./features/auth/register-page.component').then((m) => m.RegisterPageComponent),
  },
  {
    path: 'verify-email',
    loadComponent: () =>
      import('./features/auth/verify-email-page.component').then((m) => m.VerifyEmailPageComponent),
  },
  {
    path: 'mfa/verify',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/auth/mfa-verify-page.component').then((m) => m.MfaVerifyPageComponent),
  },
  {
    path: 'verbs',
    loadChildren: () =>
      import('./features/verb-search/verb-search.routes').then((m) => m.verbSearchRoutes),
  },
  {
    path: 'profile',
    loadChildren: () =>
      import('./features/profile/profile.routes').then((m) => m.profileRoutes),
  },
  {
    path: 'study',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/study/study.routes').then((m) => m.studyRoutes),
  },
  {
    path: 'progress',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/progress/progress.routes').then((m) => m.progressRoutes),
  },
  {
    path: 'admin',
    canActivate: [adminGuard],
    loadChildren: () =>
      import('./features/admin/admin.routes').then((m) => m.adminRoutes),
  },
  {
    path: 'forbidden',
    loadComponent: () =>
      import('./features/admin/forbidden-page.component').then((m) => m.ForbiddenPageComponent),
  },
  {
    path: '',
    redirectTo: 'verbs',
    pathMatch: 'full',
  },
  { path: '**', redirectTo: 'verbs' },
];
