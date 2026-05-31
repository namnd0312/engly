import { Routes } from '@angular/router';

export const profileRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./profile-page.component').then((m) => m.ProfilePageComponent),
  },
];
