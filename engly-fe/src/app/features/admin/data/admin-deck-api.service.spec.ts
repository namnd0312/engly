import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AdminDeckApiService } from './admin-deck-api.service';
import { AdminDeck, DeckRequest } from './admin-deck.model';

/**
 * Specs for AdminDeckApiService — verifies HTTP method, URL, params, body,
 * and withCredentials for every public method. Mirrors admin-verb-api.service.spec.ts.
 */

const SAMPLE_DECK: AdminDeck = {
  id: 1,
  name: 'Travel',
  slug: 'travel',
  description: 'Travel vocabulary',
  topic: 'Travel',
  createdAt: '2026-06-01T00:00:00Z',
};

const DECK_REQ: DeckRequest = {
  name: 'Travel',
  description: 'Travel vocabulary',
  topic: 'Travel',
};

const PAGE_RESPONSE = {
  content: [SAMPLE_DECK],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

describe('AdminDeckApiService', () => {
  let service: AdminDeckApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminDeckApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('list() GETs /api/admin/decks with q/page/size and withCredentials', () => {
    service.list('travel', 1, 10).subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/api/admin/decks' && r.params.get('q') === 'travel',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(PAGE_RESPONSE);
  });

  it('list() uses empty q by default', () => {
    service.list().subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/api/admin/decks',
    );
    expect(req.request.params.get('q')).toBe('');
    req.flush(PAGE_RESPONSE);
  });

  it('get() GETs /api/admin/decks/{id} with withCredentials', () => {
    service.get(1).subscribe();
    const req = httpTesting.expectOne('/api/admin/decks/1');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE_DECK);
  });

  it('create() POSTs body to /api/admin/decks with withCredentials', () => {
    service.create(DECK_REQ).subscribe();
    const req = httpTesting.expectOne('/api/admin/decks');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(DECK_REQ);
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE_DECK);
  });

  it('update() PUTs body to /api/admin/decks/{id}', () => {
    service.update(1, DECK_REQ).subscribe();
    const req = httpTesting.expectOne('/api/admin/decks/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(DECK_REQ);
    req.flush(SAMPLE_DECK);
  });

  it('delete() DELETEs /api/admin/decks/{id} with withCredentials', () => {
    service.delete(1).subscribe();
    const req = httpTesting.expectOne('/api/admin/decks/1');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(null);
  });
});
