import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { VerbDetailPageComponent } from './verb-detail-page.component';
import { VerbResponse } from './data/verb.model';

const GO_VERB: VerbResponse = {
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
};

/** Create a minimal ActivatedRoute stub with a fixed baseForm param. */
function makeActivatedRouteStub(baseForm: string) {
  return {
    paramMap: of(
      new Map([['baseForm', baseForm]]) as unknown as import('@angular/router').ParamMap
    ),
  };
}

describe('VerbDetailPageComponent', () => {
  let fixture: ComponentFixture<VerbDetailPageComponent>;
  let component: VerbDetailPageComponent;
  let httpTesting: HttpTestingController;

  async function createComponent(baseForm: string) {
    await TestBed.configureTestingModule({
      imports: [VerbDetailPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: makeActivatedRouteStub(baseForm),
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(VerbDetailPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  }

  afterEach(() => httpTesting.verify());

  it('should create', fakeAsync(async () => {
    await createComponent('go');
    fixture.detectChanges();
    httpTesting.expectOne('/api/verbs/go').flush(GO_VERB);
    tick();
    expect(component).toBeTruthy();
  }));

  it('fetches verb by baseForm and renders detail card', fakeAsync(async () => {
    await createComponent('go');
    fixture.detectChanges();

    const req = httpTesting.expectOne('/api/verbs/go');
    expect(req.request.method).toBe('GET');
    req.flush(GO_VERB);
    tick();
    fixture.detectChanges();

    expect(component.verb()).not.toBeNull();
    expect(component.verb()!.baseForm).toBe('go');
    expect(component.verb()!.pastSimple).toBe('went');
    expect(component.loading()).toBeFalse();
    expect(component.notFound()).toBeFalse();

    const nativeEl: HTMLElement = fixture.nativeElement;
    expect(nativeEl.querySelector('.verb-base-form')?.textContent).toContain('go');
    expect(nativeEl.querySelector('.detail-card')).toBeTruthy();
  }));

  it('sets notFound signal and shows not-found UI on 404', fakeAsync(async () => {
    await createComponent('zzznotaverb');
    fixture.detectChanges();

    const req = httpTesting.expectOne('/api/verbs/zzznotaverb');
    req.flush(
      { message: 'Verb not found: zzznotaverb' },
      { status: 404, statusText: 'Not Found' }
    );
    tick();
    fixture.detectChanges();

    expect(component.notFound()).toBeTrue();
    expect(component.verb()).toBeNull();
    expect(component.loading()).toBeFalse();

    const nativeEl: HTMLElement = fixture.nativeElement;
    expect(nativeEl.querySelector('.not-found-msg')).toBeTruthy();
  }));

  it('sets error signal on unexpected server error', fakeAsync(async () => {
    await createComponent('go');
    fixture.detectChanges();

    httpTesting.expectOne('/api/verbs/go').flush(
      { message: 'Internal error' },
      { status: 500, statusText: 'Internal Server Error' }
    );
    tick();
    fixture.detectChanges();

    expect(component.error()).toBeTruthy();
    expect(component.notFound()).toBeFalse();
    expect(component.loading()).toBeFalse();
  }));
});
