import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { StudySettingsPageComponent } from './study-settings-page.component';

describe('StudySettingsPageComponent', () => {
  let fixture: ComponentFixture<StudySettingsPageComponent>;
  let component: StudySettingsPageComponent;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StudySettingsPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StudySettingsPageComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function initWithSettings(newCardsPerDay: number, maxReviewsPerDay: number | null): void {
    fixture.detectChanges(); // triggers ngOnInit → GET settings
    httpTesting
      .expectOne('/api/study/settings')
      .flush({ newCardsPerDay, maxReviewsPerDay });
    fixture.detectChanges();
  }

  // -------------------------------------------------------------------------
  // Init / load
  // -------------------------------------------------------------------------

  it('should create and be loading initially', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
    expect(component.loading()).toBeTrue();
    httpTesting.expectOne('/api/study/settings').flush({ newCardsPerDay: 20, maxReviewsPerDay: null });
  });

  it('GETs /api/study/settings on init', () => {
    fixture.detectChanges();
    const req = httpTesting.expectOne('/api/study/settings');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ newCardsPerDay: 20, maxReviewsPerDay: null });
  });

  it('populates form fields from loaded settings', () => {
    initWithSettings(10, 50);
    expect(component.newCardsPerDay).toBe(10);
    expect(component.maxReviewsPerDayInput).toBe('50');
    expect(component.loading()).toBeFalse();
  });

  it('shows empty string for unlimited maxReviewsPerDay', () => {
    initWithSettings(20, null);
    expect(component.maxReviewsPerDayInput).toBe('');
  });

  it('sets loadError on settings load failure', () => {
    fixture.detectChanges();
    httpTesting
      .expectOne('/api/study/settings')
      .flush({ message: 'Server error' }, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();

    expect(component.loadError()).toBeTruthy();
    expect(component.loading()).toBeFalse();
  });

  // -------------------------------------------------------------------------
  // Save
  // -------------------------------------------------------------------------

  it('save() PUTs updated settings to /api/study/settings', () => {
    initWithSettings(20, null);
    component.newCardsPerDay = 5;
    component.maxReviewsPerDayInput = '100';

    component.save();

    const req = httpTesting.expectOne('/api/study/settings');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ newCardsPerDay: 5, maxReviewsPerDay: 100 });
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ newCardsPerDay: 5, maxReviewsPerDay: 100 });
    fixture.detectChanges();

    expect(component.saveSuccess()).toBeTrue();
    expect(component.saving()).toBeFalse();
  });

  it('save() sends null maxReviewsPerDay when input is blank (unlimited)', () => {
    initWithSettings(20, null);
    component.maxReviewsPerDayInput = '';
    component.save();

    const req = httpTesting.expectOne('/api/study/settings');
    expect(req.request.body.maxReviewsPerDay).toBeNull();
    req.flush({ newCardsPerDay: 20, maxReviewsPerDay: null });
  });

  it('save() sets saveError on failure', () => {
    initWithSettings(20, null);
    component.save();

    httpTesting
      .expectOne('/api/study/settings')
      .flush({ message: 'Bad request' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(component.saveError()).toBeTruthy();
    expect(component.saving()).toBeFalse();
  });

  it('save() is a no-op when already saving', () => {
    initWithSettings(20, null);
    component['saving'].set(true);
    component.save();
    // No request should be made; saving flag unchanged
    httpTesting.expectNone('/api/study/settings');
    expect(component.saving()).toBeTrue();
  });
});
