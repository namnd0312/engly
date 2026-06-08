import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { StudyApiService } from './study-api.service';

/**
 * Specs for StudyApiService — verifies correct HTTP method, URL, body, and withCredentials
 * for every public method. Uses HttpTestingController (no real network).
 * Mirrors admin-verb-api.service.spec.ts pattern.
 */
describe('StudyApiService', () => {
  let service: StudyApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(StudyApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  // -------------------------------------------------------------------------
  // getDecks
  // -------------------------------------------------------------------------

  it('getDecks() GETs /api/study/decks with withCredentials', () => {
    service.getDecks().subscribe();
    const req = httpTesting.expectOne('/api/study/decks');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush([]);
  });

  // -------------------------------------------------------------------------
  // getQueue
  // -------------------------------------------------------------------------

  it('getQueue(42) GETs /api/study/decks/42/queue with withCredentials', () => {
    service.getQueue(42).subscribe();
    const req = httpTesting.expectOne('/api/study/decks/42/queue');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush([]);
  });

  // -------------------------------------------------------------------------
  // review
  // -------------------------------------------------------------------------

  it('review() POSTs { wordId, rating } to /api/study/review with withCredentials', () => {
    service.review(7, 'GOOD').subscribe();
    const req = httpTesting.expectOne('/api/study/review');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ wordId: 7, rating: 'GOOD' });
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ wordId: 7, state: 'REVIEW', intervalDays: 1, dueOn: '2026-06-16', repetitions: 1, easeFactor: 2.5, lapses: 0 });
  });

  it('review() sends AGAIN rating correctly', () => {
    service.review(3, 'AGAIN').subscribe();
    const req = httpTesting.expectOne('/api/study/review');
    expect(req.request.body).toEqual({ wordId: 3, rating: 'AGAIN' });
    req.flush({});
  });

  it('review() sends HARD rating correctly', () => {
    service.review(5, 'HARD').subscribe();
    const req = httpTesting.expectOne('/api/study/review');
    expect(req.request.body).toEqual({ wordId: 5, rating: 'HARD' });
    req.flush({});
  });

  it('review() sends EASY rating correctly', () => {
    service.review(9, 'EASY').subscribe();
    const req = httpTesting.expectOne('/api/study/review');
    expect(req.request.body).toEqual({ wordId: 9, rating: 'EASY' });
    req.flush({});
  });

  // -------------------------------------------------------------------------
  // getSettings
  // -------------------------------------------------------------------------

  it('getSettings() GETs /api/study/settings with withCredentials', () => {
    service.getSettings().subscribe();
    const req = httpTesting.expectOne('/api/study/settings');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ newCardsPerDay: 20, maxReviewsPerDay: null });
  });

  // -------------------------------------------------------------------------
  // updateSettings
  // -------------------------------------------------------------------------

  it('updateSettings() PUTs the body to /api/study/settings with withCredentials', () => {
    const payload = { newCardsPerDay: 5, maxReviewsPerDay: 100 };
    service.updateSettings(payload).subscribe();
    const req = httpTesting.expectOne('/api/study/settings');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(payload);
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ newCardsPerDay: 5, maxReviewsPerDay: 100 });
  });

  it('updateSettings() sends null maxReviewsPerDay for unlimited', () => {
    service.updateSettings({ newCardsPerDay: 20, maxReviewsPerDay: null }).subscribe();
    const req = httpTesting.expectOne('/api/study/settings');
    expect(req.request.body.maxReviewsPerDay).toBeNull();
    req.flush({ newCardsPerDay: 20, maxReviewsPerDay: null });
  });
});
