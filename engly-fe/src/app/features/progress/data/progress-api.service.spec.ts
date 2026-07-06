import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ProgressApiService } from './progress-api.service';

/**
 * Specs for ProgressApiService — verifies HTTP method, URL, days query param, and
 * withCredentials for every stats endpoint. Uses HttpTestingController (no real network).
 */
describe('ProgressApiService', () => {
  let service: ProgressApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProgressApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('getSummary() GETs /api/study/stats/summary with withCredentials', () => {
    service.getSummary().subscribe();
    const req = httpTesting.expectOne('/api/study/stats/summary');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({});
  });

  it('getActivity() GETs /api/study/stats/activity with days param', () => {
    service.getActivity(30).subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/api/study/stats/activity');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('days')).toBe('30');
    expect(req.request.withCredentials).toBeTrue();
    req.flush([]);
  });

  it('getForecast() defaults to days=14', () => {
    service.getForecast().subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/api/study/stats/forecast');
    expect(req.request.params.get('days')).toBe('14');
    req.flush([]);
  });

  it('getAccuracy() GETs /api/study/stats/accuracy with days param', () => {
    service.getAccuracy(30).subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/api/study/stats/accuracy');
    expect(req.request.params.get('days')).toBe('30');
    expect(req.request.withCredentials).toBeTrue();
    req.flush([]);
  });
});
