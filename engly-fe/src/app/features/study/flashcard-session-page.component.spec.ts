import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { FlashcardSessionPageComponent } from './flashcard-session-page.component';
import { StudyCard } from './data/study.model';

/** Factory for a minimal study card. */
function makeCard(overrides: Partial<StudyCard> = {}): StudyCard {
  return {
    cardStateId: 1,
    wordId: 101,
    term: 'hello',
    meaningVi: 'xin chào',
    ipa: null,
    exampleEn: null,
    exampleVi: null,
    partOfSpeech: null,
    state: 'NEW',
    ...overrides,
  };
}

const MOCK_REVIEW_RESULT = {
  wordId: 101,
  state: 'REVIEW',
  intervalDays: 1,
  dueOn: '2026-06-16',
  repetitions: 1,
  easeFactor: 2.5,
  lapses: 0,
};

describe('FlashcardSessionPageComponent', () => {
  let fixture: ComponentFixture<FlashcardSessionPageComponent>;
  let component: FlashcardSessionPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FlashcardSessionPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => '42' } } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(FlashcardSessionPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function initWithCards(cards: StudyCard[]): void {
    fixture.detectChanges(); // triggers ngOnInit → GET queue
    httpTesting.expectOne('/api/study/decks/42/queue').flush(cards);
    fixture.detectChanges();
  }

  // -------------------------------------------------------------------------
  // Init
  // -------------------------------------------------------------------------

  it('should create and show loading before queue arrives', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
    expect(component.loading()).toBeTrue();
    httpTesting.expectOne('/api/study/decks/42/queue').flush([]);
  });

  it('GETs queue for the deck id from route params', () => {
    fixture.detectChanges();
    const req = httpTesting.expectOne('/api/study/decks/42/queue');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('populates queue signal and clears loading after queue loads', () => {
    initWithCards([makeCard()]);
    expect(component.queue().length).toBe(1);
    expect(component.loading()).toBeFalse();
  });

  // -------------------------------------------------------------------------
  // Flip behaviour
  // -------------------------------------------------------------------------

  it('flipped signal starts false', () => {
    initWithCards([makeCard()]);
    expect(component.flipped()).toBeFalse();
  });

  it('flip() sets flipped to true', () => {
    initWithCards([makeCard()]);
    component.flip();
    expect(component.flipped()).toBeTrue();
  });

  it('flip button is rendered in the DOM before flipping', () => {
    initWithCards([makeCard()]);
    const btn: HTMLElement | null = fixture.nativeElement.querySelector('.flip-btn');
    expect(btn).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // Rating — posts correct rating and advances index
  // -------------------------------------------------------------------------

  it('rate(GOOD) POSTs correct body and advances index', () => {
    initWithCards([makeCard({ wordId: 101 }), makeCard({ wordId: 102, term: 'bye' })]);

    // Flip first
    component.flip();
    fixture.detectChanges();

    component.rate('GOOD');

    const req = httpTesting.expectOne('/api/study/review');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ wordId: 101, rating: 'GOOD' });
    req.flush(MOCK_REVIEW_RESULT);
    fixture.detectChanges();

    // Index advances to 1; flipped resets
    expect(component.index()).toBe(1);
    expect(component.flipped()).toBeFalse();
  });

  it('rate(AGAIN) sends AGAIN rating', () => {
    initWithCards([makeCard({ wordId: 55 })]);
    component.flip();
    component.rate('AGAIN');
    const req = httpTesting.expectOne('/api/study/review');
    expect(req.request.body.rating).toBe('AGAIN');
    req.flush(MOCK_REVIEW_RESULT);
  });

  it('rate(EASY) sends EASY rating', () => {
    initWithCards([makeCard({ wordId: 66 })]);
    component.flip();
    component.rate('EASY');
    const req = httpTesting.expectOne('/api/study/review');
    expect(req.request.body.rating).toBe('EASY');
    req.flush(MOCK_REVIEW_RESULT);
  });

  // -------------------------------------------------------------------------
  // Done state
  // -------------------------------------------------------------------------

  it('done() is true after all cards are rated', () => {
    initWithCards([makeCard({ wordId: 10 })]);
    component.flip();
    component.rate('GOOD');
    httpTesting.expectOne('/api/study/review').flush(MOCK_REVIEW_RESULT);
    fixture.detectChanges();

    expect(component.done()).toBeTrue();
  });

  it('empty() is true when queue is empty', () => {
    initWithCards([]);
    expect(component.empty()).toBeTrue();
  });

  // -------------------------------------------------------------------------
  // 401 sets sessionExpired
  // -------------------------------------------------------------------------

  it('sets sessionExpired when queue load returns 401', () => {
    fixture.detectChanges();
    httpTesting
      .expectOne('/api/study/decks/42/queue')
      .flush({ message: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(component.sessionExpired()).toBeTrue();
    expect(component.loading()).toBeFalse();
  });

  it('sets sessionExpired when review POST returns 401', () => {
    initWithCards([makeCard({ wordId: 77 })]);
    component.flip();
    component.rate('GOOD');

    httpTesting
      .expectOne('/api/study/review')
      .flush({ message: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(component.sessionExpired()).toBeTrue();
  });

  it('sets ratingError on non-401 review failure', () => {
    initWithCards([makeCard({ wordId: 88 })]);
    component.flip();
    component.rate('HARD');

    httpTesting
      .expectOne('/api/study/review')
      .flush({ message: 'Server error' }, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();

    expect(component.ratingError()).toBeTruthy();
    expect(component.sessionExpired()).toBeFalse();
  });
});
