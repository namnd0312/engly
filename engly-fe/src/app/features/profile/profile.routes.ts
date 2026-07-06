import { Routes } from '@angular/router';
import { authGuard } from '../../core/auth.guard';

export const profileRoutes: Routes = [
  {
    path: '',
    canActivate: [authGuard], // profile now mutates data — anonymous users go to login
    loadComponent: () =>
      import('./profile-page.component').then((m) => m.ProfilePageComponent),
  },
];
