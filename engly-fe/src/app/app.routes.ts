import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: 'verbs',
    loadChildren: () =>
      import('./features/verb-search/verb-search.routes').then((m) => m.verbSearchRoutes),
  },
  {
    path: '',
    redirectTo: 'verbs',
    pathMatch: 'full',
  },
  // M5: catch-all — unknown paths redirect to verbs instead of rendering nothing
  { path: '**', redirectTo: 'verbs' },
];
