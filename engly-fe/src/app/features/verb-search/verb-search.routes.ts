import { Routes } from '@angular/router';

/** Feature routes for the verb-search module. Lazy-loaded from app.routes.ts. */
export const verbSearchRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./verb-search-page.component').then((m) => m.VerbSearchPageComponent),
  },
  {
    path: ':baseForm',
    loadComponent: () =>
      import('./verb-detail-page.component').then((m) => m.VerbDetailPageComponent),
  },
];
