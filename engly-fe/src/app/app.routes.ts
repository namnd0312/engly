import { Routes } from '@angular/router';
import { adminGuard } from './features/admin/admin.guard';

export const routes: Routes = [
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
  // M5: catch-all — unknown paths redirect to verbs instead of rendering nothing
  { path: '**', redirectTo: 'verbs' },
];
