import { Routes } from '@angular/router';

/** Feature routes for the SRS study module. Lazy-loaded from app.routes.ts under /study. */
export const studyRoutes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./deck-list-page.component').then((m) => m.DeckListPageComponent),
  },
  {
    path: 'settings',
    loadComponent: () =>
      import('./study-settings-page.component').then((m) => m.StudySettingsPageComponent),
  },
  {
    path: ':deckId',
    loadComponent: () =>
      import('./flashcard-session-page.component').then((m) => m.FlashcardSessionPageComponent),
  },
];
