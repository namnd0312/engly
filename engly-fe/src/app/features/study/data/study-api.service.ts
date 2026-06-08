import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  StudyDeckSummary,
  StudyCard,
  ReviewResult,
  ReviewRating,
  StudySettings,
  UpdateSettingsRequest,
} from './study.model';

/**
 * HTTP service for the SRS study API (/api/study/**).
 * All calls use withCredentials:true — session cookie flows via BFF.
 * Mutations (POST review, PUT settings) carry X-XSRF-TOKEN auto-attached
 * by the global withXsrfConfiguration interceptor in app.config.ts.
 */
@Injectable({ providedIn: 'root' })
export class StudyApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/study';

  /** GET /api/study/decks → list of deck summaries with new/due/learned counts. */
  getDecks(): Observable<StudyDeckSummary[]> {
    return this.http.get<StudyDeckSummary[]>(`${this.base}/decks`, {
      withCredentials: true,
    });
  }

  /** GET /api/study/decks/{deckId}/queue → ordered study queue for this session. */
  getQueue(deckId: number): Observable<StudyCard[]> {
    return this.http.get<StudyCard[]>(`${this.base}/decks/${deckId}/queue`, {
      withCredentials: true,
    });
  }

  /** POST /api/study/review → submit self-rating for a card. */
  review(wordId: number, rating: ReviewRating): Observable<ReviewResult> {
    return this.http.post<ReviewResult>(
      `${this.base}/review`,
      { wordId, rating },
      { withCredentials: true },
    );
  }

  /** GET /api/study/settings → current user SRS settings. */
  getSettings(): Observable<StudySettings> {
    return this.http.get<StudySettings>(`${this.base}/settings`, {
      withCredentials: true,
    });
  }

  /** PUT /api/study/settings → save updated SRS settings. */
  updateSettings(req: UpdateSettingsRequest): Observable<StudySettings> {
    return this.http.put<StudySettings>(`${this.base}/settings`, req, {
      withCredentials: true,
    });
  }
}
