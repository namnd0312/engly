import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AdminVerbApiService } from './admin-verb-api.service';
import { VerbAdminResponse, VerbRequest } from './admin-verb.model';

const SAMPLE: VerbAdminResponse = {
  id: 1,
  baseForm: 'go',
  pastSimple: 'went',
  pastParticiple: 'gone',
  pastSimpleAlt: null,
  pastParticipleAlt: null,
  ipaBase: null,
  ipaPast: null,
  ipaPp: null,
  meaningVi: 'đi',
  verified: false,
};

const REQ: VerbRequest = {
  baseForm: 'go',
  pastSimple: 'went',
  pastParticiple: 'gone',
  pastSimpleAlt: null,
  pastParticipleAlt: null,
  ipaBase: null,
  ipaPast: null,
  ipaPp: null,
  meaningVi: 'đi',
  verified: false,
};

describe('AdminVerbApiService', () => {
  let service: AdminVerbApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminVerbApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('list() GETs /api/admin/verbs with q/page/size and withCredentials', () => {
    service.list('go', 1, 10).subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/api/admin/verbs' && r.params.get('q') === 'go',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ content: [], page: 1, size: 10, totalElements: 0, totalPages: 0 });
  });

  it('get() GETs /api/admin/verbs/{id}', () => {
    service.get(1).subscribe();
    const req = httpTesting.expectOne('/api/admin/verbs/1');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE);
  });

  it('create() POSTs the body to /api/admin/verbs', () => {
    service.create(REQ).subscribe();
    const req = httpTesting.expectOne('/api/admin/verbs');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(REQ);
    expect(req.request.withCredentials).toBeTrue();
    req.flush(SAMPLE);
  });

  it('update() PUTs to /api/admin/verbs/{id}', () => {
    service.update(1, REQ).subscribe();
    const req = httpTesting.expectOne('/api/admin/verbs/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(REQ);
    req.flush(SAMPLE);
  });

  it('delete() DELETEs /api/admin/verbs/{id}', () => {
    service.delete(1).subscribe();
    const req = httpTesting.expectOne('/api/admin/verbs/1');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(null);
  });

  it('toggleVerified() PATCHes /{id}/verified with {verified}', () => {
    service.toggleVerified(1, true).subscribe();
    const req = httpTesting.expectOne('/api/admin/verbs/1/verified');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ verified: true });
    req.flush({ ...SAMPLE, verified: true });
  });
});
