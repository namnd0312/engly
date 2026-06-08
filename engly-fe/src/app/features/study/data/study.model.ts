/** Models matching Phase 03 backend DTOs for the SRS study API. */

export type ReviewRating = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY';

/** Summary of a deck shown on the deck-list page. */
export interface StudyDeckSummary {
  deckId: number;
  name: string;
  slug: string;
  topic: string;
  newCount: number;
  dueCount: number;
  learnedCount: number;
}

/** A single flashcard in the study queue (term → meaning direction). */
export interface StudyCard {
  cardStateId: number;
  wordId: number;
  term: string;
  meaningVi: string;
  ipa: string | null;
  exampleEn: string | null;
  exampleVi: string | null;
  partOfSpeech: string | null;
  state: string;
}

/** Response from POST /api/study/review. */
export interface ReviewResult {
  wordId: number;
  state: string;
  intervalDays: number;
  dueOn: string;
  repetitions: number;
  easeFactor: number;
  lapses: number;
}

/** User SRS settings. */
export interface StudySettings {
  newCardsPerDay: number;
  maxReviewsPerDay: number | null;
}

/** Request body for PUT /api/study/settings. */
export interface UpdateSettingsRequest {
  newCardsPerDay: number;
  maxReviewsPerDay?: number | null;
}
