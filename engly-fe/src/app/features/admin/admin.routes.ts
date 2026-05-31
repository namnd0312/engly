import { Routes } from '@angular/router';

/**
 * Admin feature routes (children of the guarded /admin parent in app.routes.ts).
 * No extra guard needed here — the parent canActivate already enforces ROLE_ADMIN.
 */
export const adminRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./admin-list-page.component').then((m) => m.AdminListPageComponent),
  },
  {
    path: 'verbs/new',
    loadComponent: () =>
      import('./admin-form-page.component').then((m) => m.AdminFormPageComponent),
  },
  {
    path: 'verbs/:id/edit',
    loadComponent: () =>
      import('./admin-form-page.component').then((m) => m.AdminFormPageComponent),
  },
];
