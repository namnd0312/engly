import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { VocabWordAdminFormPageComponent } from './vocab-word-admin-form-page.component';
import { AdminDeck } from './data/admin-deck.model';
import { AdminVocabWord } from './data/admin-vocab-word.model';

const SAMPLE_DECK: AdminDeck = {
  id: 1,
  name: 'Travel',
  slug: 'travel',
  description: null,
  topic: 'Travel',
  createdAt: '2026-06-01T00:00:00Z',
};

const SAMPLE_WORD: AdminVocabWord = {
  id: 10,
  deckId: 1,
  term: 'hello',
  meaningVi: 'xin chào',
  ipa: null,
  exampleEn: null,
  exampleVi: null,
  partOfSpeech: null,
  verified: false,
  createdAt: '2026-06-01T00:00:00Z',
};

const DECKS_PAGE = {
  content: [SAMPLE_DECK],
  page: 0,
  size: 200,
  totalElements: 1,
  totalPages: 1,
};

/** Helper: flush the deck list request that always fires on init. */
function flushDecks(httpTesting: HttpTestingController): void {
  httpTesting
    .expectOne((r) => r.url === '/api/admin/decks')
    .flush(DECKS_PAGE);
}

describe('VocabWordAdminFormPageComponent — create mode (no id param)', () => {
  let fixture: ComponentFixture<VocabWordAdminFormPageComponent>;
  let component: VocabWordAdminFormPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VocabWordAdminFormPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        // No id param → create mode
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => null } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(VocabWordAdminFormPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('should create and load deck options on init', () => {
    fixture.detectChanges();
    flushDecks(httpTesting);
    fixture.detectChanges();

    expect(component).toBeTruthy();
    expect(component.decks().length).toBe(1);
    expect(component.editId()).toBeNull();
  });

  it('renders the "New vocab word" title in create mode', () => {
    fixture.detectChanges();
    flushDecks(httpTesting);
    fixture.detectChanges();

    const title: HTMLElement | null = fixture.nativeElement.querySelector('.page-title');
    expect(title?.textContent).toContain('New vocab word');
  });

  it('form is initially invalid (required fields empty)', () => {
    fixture.detectChanges();
    flushDecks(httpTesting);
    expect(component.form.invalid).toBeTrue();
  });

  it('onSubmit() POSTs to /api/admin/vocab-words when form is valid', () => {
    fixture.detectChanges();
    flushDecks(httpTesting);

    component.form.patchValue({
      deckId: 1,
      term: 'hello',
      meaningVi: 'xin chào',
      ipa: '',
      exampleEn: '',
      exampleVi: '',
      partOfSpeech: '',
    });
    fixture.detectChanges();

    component.onSubmit();

    const req = httpTesting.expectOne('/api/admin/vocab-words');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.term).toBe('hello');
    expect(req.request.body.deckId).toBe(1);
    req.flush(SAMPLE_WORD);
  });

  it('onSubmit() does nothing when form is invalid', () => {
    fixture.detectChanges();
    flushDecks(httpTesting);

    // form is invalid (term/meaningVi empty)
    component.onSubmit();
    httpTesting.expectNone('/api/admin/vocab-words');
  });

  it('sets serverError on 404 response (missing deck)', () => {
    fixture.detectChanges();
    flushDecks(httpTesting);

    component.form.patchValue({ deckId: 1, term: 'test', meaningVi: 'kiểm tra' });
    component.onSubmit();

    httpTesting
      .expectOne('/api/admin/vocab-words')
      .flush({ message: 'Not found' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(component.serverError()).toContain('deck not found');
  });
});

describe('VocabWordAdminFormPageComponent — edit mode (with id param)', () => {
  let fixture: ComponentFixture<VocabWordAdminFormPageComponent>;
  let component: VocabWordAdminFormPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VocabWordAdminFormPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        // id=10 → edit mode
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => '10' } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(VocabWordAdminFormPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function initEditMode(): void {
    fixture.detectChanges();
    flushDecks(httpTesting);
    httpTesting.expectOne('/api/admin/vocab-words/10').flush(SAMPLE_WORD);
    fixture.detectChanges();
  }

  it('sets editId and loads existing word into form', () => {
    initEditMode();
    expect(component.editId()).toBe(10);
    expect(component.form.getRawValue().term).toBe('hello');
    expect(component.form.getRawValue().meaningVi).toBe('xin chào');
  });

  it('renders "Edit vocab word" title in edit mode', () => {
    initEditMode();
    const title: HTMLElement | null = fixture.nativeElement.querySelector('.page-title');
    expect(title?.textContent).toContain('Edit vocab word');
  });

  it('onSubmit() PUTs to /api/admin/vocab-words/10 in edit mode', () => {
    initEditMode();
    component.onSubmit();

    const req = httpTesting.expectOne('/api/admin/vocab-words/10');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.term).toBe('hello');
    req.flush(SAMPLE_WORD);
  });
});
