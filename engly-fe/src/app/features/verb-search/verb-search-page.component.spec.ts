import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { VerbSearchPageComponent } from './verb-search-page.component';
import { VerbSearchPageResponse } from './data/verb.model';

/** Minimal page-response factory */
function makePageResponse(overrides: Partial<VerbSearchPageResponse> = {}): VerbSearchPageResponse {
  return {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    ...overrides,
  };
}

describe('VerbSearchPageComponent', () => {
  let fixture: ComponentFixture<VerbSearchPageComponent>;
  let component: VerbSearchPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VerbSearchPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(VerbSearchPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('should create', fakeAsync(() => {
    fixture.detectChanges(); // triggers ngOnInit + setValue('')

    // Flush the initial empty-query request triggered by ngOnInit
    tick(300);
    const req = httpTesting.expectOne((r) => r.url.includes('/api/verbs'));
    req.flush(makePageResponse());

    expect(component).toBeTruthy();
  }));

  it('debounces search input and fires GET /api/verbs with q param', fakeAsync(() => {
    fixture.detectChanges();

    // flush initial load
    tick(300);
    httpTesting.expectOne((r) => r.url.includes('/api/verbs')).flush(makePageResponse());

    // type into search control
    component.searchControl.setValue('go');
    tick(299); // not yet debounced
    httpTesting.expectNone((r) => r.url.includes('/api/verbs') && r.params.get('q') === 'go');

    tick(1); // debounce fires at 300ms
    const req = httpTesting.expectOne((r) =>
      r.url.includes('/api/verbs') && r.params.get('q') === 'go'
    );
    expect(req.request.method).toBe('GET');

    const mockResponse = makePageResponse({
      content: [
        {
          baseForm: 'go',
          pastSimple: 'went',
          pastSimpleAlt: null,
          pastParticiple: 'gone',
          pastParticipleAlt: null,
          ipaBase: '/ɡoʊ/',
          ipaPast: '/wɛnt/',
          ipaPp: '/ɡɔn/',
          meaningVi: 'đi',
          verified: false,
        },
      ],
      totalElements: 1,
    });
    req.flush(mockResponse);
    fixture.detectChanges();

    // results signal populated
    expect(component.results().length).toBe(1);
    expect(component.results()[0].baseForm).toBe('go');
    expect(component.totalElements()).toBe(1);
  }));

  it('shows loading state while request is in flight', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    // loading should be true before flushing
    expect(component.loading()).toBeTrue();

    httpTesting.expectOne((r) => r.url.includes('/api/verbs')).flush(makePageResponse());
    fixture.detectChanges();
    expect(component.loading()).toBeFalse();
  }));

  it('sets error signal on HTTP failure', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);

    const req = httpTesting.expectOne((r) => r.url.includes('/api/verbs'));
    req.flush({ message: 'Server error' }, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();

    expect(component.error()).toBeTruthy();
    expect(component.loading()).toBeFalse();
  }));

  it('shows empty-state message when results are empty and not loading', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    httpTesting.expectOne((r) => r.url.includes('/api/verbs')).flush(makePageResponse());
    fixture.detectChanges();

    const nativeEl: HTMLElement = fixture.nativeElement;
    expect(nativeEl.querySelector('.empty-msg')).toBeTruthy();
  }));

  it('renders verb rows in table when results are present', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    httpTesting.expectOne((r) => r.url.includes('/api/verbs')).flush(
      makePageResponse({
        content: [
          {
            baseForm: 'run',
            pastSimple: 'ran',
            pastSimpleAlt: null,
            pastParticiple: 'run',
            pastParticipleAlt: null,
            ipaBase: '/rʌn/',
            ipaPast: '/ræn/',
            ipaPp: '/rʌn/',
            meaningVi: 'chạy',
            verified: false,
          },
        ],
        totalElements: 1,
      })
    );
    fixture.detectChanges();

    const nativeEl: HTMLElement = fixture.nativeElement;
    const rows = nativeEl.querySelectorAll('.verb-row');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('run');
  }));
});
