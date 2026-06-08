import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { DeckListPageComponent } from './deck-list-page.component';
import { StudyDeckSummary } from './data/study.model';

/** Factory for a minimal deck summary. */
function makeDeck(overrides: Partial<StudyDeckSummary> = {}): StudyDeckSummary {
  return {
    deckId: 1,
    name: 'Travel',
    slug: 'travel',
    topic: 'Travel',
    newCount: 20,
    dueCount: 0,
    learnedCount: 0,
    ...overrides,
  };
}

describe('DeckListPageComponent', () => {
  let fixture: ComponentFixture<DeckListPageComponent>;
  let component: DeckListPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DeckListPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DeckListPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function flushDecks(decks: StudyDeckSummary[]): void {
    fixture.detectChanges(); // triggers ngOnInit
    httpTesting.expectOne('/api/study/decks').flush(decks);
    fixture.detectChanges();
  }

  it('should create and show loading initially', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
    expect(component.loading()).toBeTrue();
    httpTesting.expectOne('/api/study/decks').flush([]);
  });

  it('loads decks on init and populates decks signal', () => {
    flushDecks([makeDeck()]);
    expect(component.decks().length).toBe(1);
    expect(component.decks()[0].name).toBe('Travel');
    expect(component.loading()).toBeFalse();
  });

  it('sets error signal on HTTP failure', () => {
    fixture.detectChanges();
    httpTesting
      .expectOne('/api/study/decks')
      .flush({ message: 'Server error' }, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();

    expect(component.error()).toBeTruthy();
    expect(component.loading()).toBeFalse();
  });

  it('shows empty message when no decks returned', () => {
    flushDecks([]);
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.empty-msg')).toBeTruthy();
  });

  it('renders a deck card for each deck', () => {
    flushDecks([makeDeck({ deckId: 1, name: 'Travel' }), makeDeck({ deckId: 2, name: 'Business' })]);
    const el: HTMLElement = fixture.nativeElement;
    const cards = el.querySelectorAll('.deck-card');
    expect(cards.length).toBe(2);
    expect(cards[0].textContent).toContain('Travel');
    expect(cards[1].textContent).toContain('Business');
  });

  it('disables study button when newCount and dueCount are both 0', () => {
    flushDecks([makeDeck({ newCount: 0, dueCount: 0 })]);
    const btn: HTMLButtonElement | null = fixture.nativeElement.querySelector('.study-btn');
    expect(btn).toBeTruthy();
    expect(btn!.disabled).toBeTrue();
  });

  it('enables study button when newCount > 0', () => {
    flushDecks([makeDeck({ newCount: 5, dueCount: 0 })]);
    const btn: HTMLButtonElement | null = fixture.nativeElement.querySelector('.study-btn');
    expect(btn!.disabled).toBeFalse();
  });
});
