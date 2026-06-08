import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { VocabImportApiService } from './vocab-import-api.service';
import { CreateImportRequest, ImportJob, ImportErrorPage } from './vocab-import.model';

/**
 * Specs for VocabImportApiService — verifies HTTP method, URL, params, body,
 * and withCredentials for every public method.
 */

const SAMPLE_JOB: ImportJob = {
  id: 1,
  deckId: 2,
  status: 'RUNNING',
  fileSize: 1024,
  processedCount: 10,
  insertedCount: 8,
  skippedCount: 1,
  errorCount: 1,
  percent: 50,
  createdAt: '2026-06-05T00:00:00Z',
};

const SAMPLE_CREATE_REQ: CreateImportRequest = { deckId: 2, uploadId: 'abc123' };

const SAMPLE_ERROR_PAGE: ImportErrorPage = {
  content: [{ lineNo: 3, rawExcerpt: 'bad,row', reason: 'Missing field' }],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

describe('VocabImportApiService', () => {
  let service: VocabImportApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(VocabImportApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('create() POSTs body to /api/admin/vocab-imports with withCredentials', () => {
    service.create(SAMPLE_CREATE_REQ).subscribe();
    const req = http.expectOne('/api/admin/vocab-imports');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(SAMPLE_CREATE_REQ);
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ jobId: 1, status: 'PENDING' });
  });

  it('getJob() GETs /api/admin/vocab-imports/{id} with withCredentials', () => {
    service.getJob(1).subscribe();
    const req = http.expectOne('/api/admin/vocab-imports/1');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE_JOB);
  });

  it('getErrors() GETs /api/admin/vocab-imports/{id}/errors with page and size params', () => {
    service.getErrors(1, 2, 10).subscribe();
    const req = http.expectOne(
      (r) => r.url === '/api/admin/vocab-imports/1/errors'
        && r.params.get('page') === '2'
        && r.params.get('size') === '10',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE_ERROR_PAGE);
  });

  it('getErrors() uses default page=0, size=20', () => {
    service.getErrors(1).subscribe();
    const req = http.expectOne(
      (r) => r.url === '/api/admin/vocab-imports/1/errors',
    );
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    req.flush(SAMPLE_ERROR_PAGE);
  });

  it('retry() POSTs null to /api/admin/vocab-imports/{id}/retry with withCredentials', () => {
    service.retry(1).subscribe();
    const req = http.expectOne('/api/admin/vocab-imports/1/retry');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ jobId: 1, status: 'PENDING' });
  });
});
