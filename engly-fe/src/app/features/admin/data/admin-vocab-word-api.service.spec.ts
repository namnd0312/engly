import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AdminVocabWordApiService } from './admin-vocab-word-api.service';
import { AdminVocabWord, VocabWordRequest } from './admin-vocab-word.model';

/**
 * Specs for AdminVocabWordApiService — verifies HTTP method, URL, params, body,
 * and withCredentials. Mirrors admin-verb-api.service.spec.ts pattern.
 */

const SAMPLE_WORD: AdminVocabWord = {
  id: 10,
  deckId: 1,
  term: 'hello',
  meaningVi: 'xin chào',
  ipa: '/həˈloʊ/',
  exampleEn: null,
  exampleVi: null,
  partOfSpeech: 'exclamation',
  verified: false,
  createdAt: '2026-06-01T00:00:00Z',
};

const WORD_REQ: VocabWordRequest = {
  deckId: 1,
  term: 'hello',
  meaningVi: 'xin chào',
  ipa: '/həˈloʊ/',
  exampleEn: null,
  exampleVi: null,
  partOfSpeech: 'exclamation',
};

const PAGE_RESPONSE = {
  content: [SAMPLE_WORD],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

describe('AdminVocabWordApiService', () => {
  let service: AdminVocabWordApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminVocabWordApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('list() GETs /api/admin/vocab-words with q/page/size and withCredentials', () => {
    service.list('hello', 0, 10).subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/api/admin/vocab-words' && r.params.get('q') === 'hello',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(PAGE_RESPONSE);
  });

  it('list() appends deckId param when provided', () => {
    service.list('', 0, 20, 3).subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/api/admin/vocab-words' && r.params.get('deckId') === '3',
    );
    expect(req.request.params.get('deckId')).toBe('3');
    req.flush(PAGE_RESPONSE);
  });

  it('list() omits deckId param when not provided', () => {
    service.list().subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/api/admin/vocab-words',
    );
    expect(req.request.params.has('deckId')).toBeFalse();
    req.flush(PAGE_RESPONSE);
  });

  it('get() GETs /api/admin/vocab-words/{id} with withCredentials', () => {
    service.get(10).subscribe();
    const req = httpTesting.expectOne('/api/admin/vocab-words/10');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE_WORD);
  });

  it('create() POSTs body to /api/admin/vocab-words with withCredentials', () => {
    service.create(WORD_REQ).subscribe();
    const req = httpTesting.expectOne('/api/admin/vocab-words');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(WORD_REQ);
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE_WORD);
  });

  it('update() PUTs body to /api/admin/vocab-words/{id}', () => {
    service.update(10, WORD_REQ).subscribe();
    const req = httpTesting.expectOne('/api/admin/vocab-words/10');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(WORD_REQ);
    req.flush(SAMPLE_WORD);
  });

  it('delete() DELETEs /api/admin/vocab-words/{id} with withCredentials', () => {
    service.delete(10).subscribe();
    const req = httpTesting.expectOne('/api/admin/vocab-words/10');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(null);
  });

  it('toggleVerified() PATCHes /{id}/verified with { verified }', () => {
    service.toggleVerified(10, true).subscribe();
    const req = httpTesting.expectOne('/api/admin/vocab-words/10/verified');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ verified: true });
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ ...SAMPLE_WORD, verified: true });
  });
});
