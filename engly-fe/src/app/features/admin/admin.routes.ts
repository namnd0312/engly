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
  // --- Irregular verbs ---
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
  // --- Vocab decks ---
  {
    path: 'decks',
    loadComponent: () =>
      import('./deck-admin-list-page.component').then((m) => m.DeckAdminListPageComponent),
  },
  {
    path: 'decks/new',
    loadComponent: () =>
      import('./deck-admin-form-page.component').then((m) => m.DeckAdminFormPageComponent),
  },
  {
    path: 'decks/:id/edit',
    loadComponent: () =>
      import('./deck-admin-form-page.component').then((m) => m.DeckAdminFormPageComponent),
  },
  // --- Vocab words ---
  {
    path: 'vocab-words',
    loadComponent: () =>
      import('./vocab-word-admin-list-page.component').then((m) => m.VocabWordAdminListPageComponent),
  },
  {
    path: 'vocab-words/new',
    loadComponent: () =>
      import('./vocab-word-admin-form-page.component').then((m) => m.VocabWordAdminFormPageComponent),
  },
  {
    path: 'vocab-words/:id/edit',
    loadComponent: () =>
      import('./vocab-word-admin-form-page.component').then((m) => m.VocabWordAdminFormPageComponent),
  },
  // --- CSV Vocab Import ---
  {
    path: 'vocab-imports',
    loadComponent: () =>
      import('./vocab-import-page.component').then((m) => m.VocabImportPageComponent),
  },
];
