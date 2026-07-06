import { Routes } from '@angular/router';

/** Feature routes for the progress dashboard. Lazy-loaded from app.routes.ts under /progress. */
export const progressRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./progress-dashboard-page.component').then((m) => m.ProgressDashboardPageComponent),
  },
];
