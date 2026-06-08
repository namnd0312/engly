import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { DeckAdminListPageComponent } from './deck-admin-list-page.component';
import { AdminDeck } from './data/admin-deck.model';

/** Minimal deck factory. */
function makeDeck(overrides: Partial<AdminDeck> = {}): AdminDeck {
  return {
    id: 1,
    name: 'Travel',
    slug: 'travel',
    description: null,
    topic: 'Travel',
    createdAt: '2026-06-01T00:00:00Z',
    ...overrides,
  };
}

const EMPTY_PAGE = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

describe('DeckAdminListPageComponent', () => {
  let fixture: ComponentFixture<DeckAdminListPageComponent>;
  let component: DeckAdminListPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DeckAdminListPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DeckAdminListPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('should create', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    httpTesting.expectOne((r) => r.url.includes('/api/admin/decks')).flush(EMPTY_PAGE);
    expect(component).toBeTruthy();
  }));

  it('fires GET /api/admin/decks on init via debounced searchControl', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    const req = httpTesting.expectOne((r) => r.url.includes('/api/admin/decks'));
    expect(req.request.method).toBe('GET');
    req.flush(EMPTY_PAGE);
  }));

  it('populates decks signal after successful load', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    httpTesting
      .expectOne((r) => r.url.includes('/api/admin/decks'))
      .flush({ ...EMPTY_PAGE, content: [makeDeck()], totalElements: 1 });
    fixture.detectChanges();

    expect(component.decks().length).toBe(1);
    expect(component.decks()[0].name).toBe('Travel');
    expect(component.totalElements()).toBe(1);
    expect(component.loading()).toBeFalse();
  }));

  it('sets error signal on HTTP failure', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    httpTesting
      .expectOne((r) => r.url.includes('/api/admin/decks'))
      .flush({ message: 'Error' }, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();

    expect(component.error()).toBeTruthy();
  }));

  it('shows empty message when no decks', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    httpTesting.expectOne((r) => r.url.includes('/api/admin/decks')).flush(EMPTY_PAGE);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.empty-msg')).toBeTruthy();
  }));

  it('debounces search input and fires GET with q param', fakeAsync(() => {
    fixture.detectChanges();
    // Flush initial load
    tick(300);
    httpTesting.expectOne((r) => r.url.includes('/api/admin/decks')).flush(EMPTY_PAGE);

    // Type into search
    component.searchControl.setValue('business');
    tick(299);
    httpTesting.expectNone((r) => r.params.get('q') === 'business');

    tick(1);
    const req = httpTesting.expectOne(
      (r) => r.url.includes('/api/admin/decks') && r.params.get('q') === 'business',
    );
    req.flush({ ...EMPTY_PAGE, content: [makeDeck({ name: 'Business', slug: 'business' })], totalElements: 1 });
    fixture.detectChanges();

    expect(component.decks()[0].name).toBe('Business');
  }));

  it('renders a table row for each deck', fakeAsync(() => {
    fixture.detectChanges();
    tick(300);
    httpTesting
      .expectOne((r) => r.url.includes('/api/admin/decks'))
      .flush({ ...EMPTY_PAGE, content: [makeDeck(), makeDeck({ id: 2, name: 'Business', slug: 'business' })], totalElements: 2 });
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.data-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Travel');
    expect(rows[1].textContent).toContain('Business');
  }));
});
