import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { VocabImportPageComponent } from './vocab-import-page.component';
import { TusUploaderService, TusUploadOptions } from './data/tus-uploader.service';
import { AuthService } from '../../core/auth.service';
import { ImportJob } from './data/vocab-import.model';

/**
 * Specs for VocabImportPageComponent.
 * TusUploaderService is stubbed so no real XHR fires.
 * AuthService is stubbed to return a token signal.
 */

function makeJob(overrides: Partial<ImportJob> = {}): ImportJob {
  return {
    id: 1, deckId: 2, status: 'RUNNING', fileSize: 1024,
    processedCount: 5, insertedCount: 4, skippedCount: 0,
    errorCount: 0, percent: 50, createdAt: '2026-06-05T00:00:00Z',
    ...overrides,
  };
}

const DECK_PAGE = {
  content: [
    { id: 1, name: 'Travel', slug: 'travel', description: null, topic: null, createdAt: '2026-06-01T00:00:00Z' },
    { id: 2, name: 'Business', slug: 'business', description: null, topic: null, createdAt: '2026-06-01T00:00:00Z' },
  ],
  page: 0, size: 200, totalElements: 2, totalPages: 1,
};

const EMPTY_DECK_PAGE = { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 };

// Captured callbacks from the tus stub
let capturedOpts: TusUploadOptions | null = null;

const tusSpy = {
  upload: jasmine.createSpy('upload').and.callFake((_file: File, opts: TusUploadOptions) => {
    capturedOpts = opts;
    return { abort: jasmine.createSpy('abort') };
  }),
};

describe('VocabImportPageComponent', () => {
  let fixture: ComponentFixture<VocabImportPageComponent>;
  let component: VocabImportPageComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    capturedOpts = null;
    tusSpy.upload.calls.reset();

    await TestBed.configureTestingModule({
      imports: [VocabImportPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: TusUploaderService, useValue: tusSpy },
        {
          provide: AuthService,
          useValue: {
            user: () => null,
            isAuthenticated: () => false,
            isAdmin: () => true,
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(VocabImportPageComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('should create the component', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(EMPTY_DECK_PAGE);
    expect(component).toBeTruthy();
  }));

  it('loads decks on init and populates decks signal', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(DECK_PAGE);
    fixture.detectChanges();

    expect(component.decks().length).toBe(2);
    expect(component.decks()[0].name).toBe('Travel');
  }));

  it('renders deck options in select after load', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(DECK_PAGE);
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#deck-select');
    // 1 placeholder + 2 decks
    expect(select.options.length).toBe(3);
  }));

  it('startUpload() calls TusUploaderService.upload with the selected file', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(DECK_PAGE);

    component.selectedDeckId.set(1);
    const testFile = new File(['word,def\ncat,gato'], 'test.csv', { type: 'text/csv' });
    component.file.set(testFile);

    component.startUpload();

    expect(tusSpy.upload).toHaveBeenCalledOnceWith(
      jasmine.any(File),
      jasmine.objectContaining({ onProgress: jasmine.any(Function) }),
    );
    expect(component.uploading()).toBeTrue();
  }));

  it('onSuccess callback creates import job and starts polling', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(DECK_PAGE);

    component.selectedDeckId.set(1);
    component.file.set(new File(['a,b'], 'test.csv'));
    component.startUpload();

    // Simulate tus success
    capturedOpts!.onSuccess('upload-id-xyz');
    fixture.detectChanges();

    expect(component.creating()).toBeTrue();

    // Flush POST /api/admin/vocab-imports
    http.expectOne('/api/admin/vocab-imports').flush({ jobId: 7, status: 'PENDING' });
    tick();
    fixture.detectChanges();

    expect(component.polling()).toBeTrue();

    // Flush first poll (job still running)
    tick(2000);
    http.expectOne('/api/admin/vocab-imports/7').flush(makeJob({ id: 7, status: 'RUNNING', percent: 30 }));
    fixture.detectChanges();

    expect(component.job()?.status).toBe('RUNNING');
    expect(component.job()?.percent).toBe(30);

    // Flush second poll → COMPLETED
    tick(2000);
    http.expectOne('/api/admin/vocab-imports/7').flush(makeJob({ id: 7, status: 'COMPLETED', percent: 100 }));
    // inclusive takeWhile emits COMPLETED then completes
    tick(2000);
    // no more requests expected (takeWhile stops after terminal state)
    fixture.detectChanges();

    expect(component.job()?.status).toBe('COMPLETED');
    expect(component.polling()).toBeFalse();
  }));

  it('polling stops after FAILED terminal state', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(DECK_PAGE);

    component.selectedDeckId.set(1);
    component.file.set(new File(['a,b'], 'test.csv'));
    component.startUpload();
    capturedOpts!.onSuccess('uid-fail');

    http.expectOne('/api/admin/vocab-imports').flush({ jobId: 9, status: 'PENDING' });
    tick();

    tick(2000);
    http.expectOne('/api/admin/vocab-imports/9').flush(
      makeJob({ id: 9, status: 'FAILED', percent: 60, errorCount: 3 }),
    );
    tick(2000);
    fixture.detectChanges();

    expect(component.job()?.status).toBe('FAILED');
    expect(component.polling()).toBeFalse();

    // errors should load automatically
    const errReq = http.expectOne(
      (r) => r.url === '/api/admin/vocab-imports/9/errors',
    );
    errReq.flush({
      content: [{ lineNo: 2, rawExcerpt: 'x,y', reason: 'bad' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    fixture.detectChanges();

    expect(component.errorsPage()?.content.length).toBe(1);
  }));

  it('retry() calls POST /api/admin/vocab-imports/{id}/retry and resumes polling', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(DECK_PAGE);

    component.selectedDeckId.set(1);
    component.file.set(new File(['a,b'], 'test.csv'));
    component.startUpload();
    capturedOpts!.onSuccess('uid-retry');

    http.expectOne('/api/admin/vocab-imports').flush({ jobId: 5, status: 'PENDING' });
    tick();

    tick(2000);
    http.expectOne('/api/admin/vocab-imports/5').flush(makeJob({ id: 5, status: 'FAILED', percent: 20 }));
    tick(2000);
    fixture.detectChanges();

    component.retry();
    const retryReq = http.expectOne('/api/admin/vocab-imports/5/retry');
    retryReq.flush({ jobId: 5, status: 'PENDING' });
    tick();

    expect(component.polling()).toBeTrue();

    // Complete the new poll
    tick(2000);
    http.expectOne('/api/admin/vocab-imports/5').flush(makeJob({ id: 5, status: 'COMPLETED', percent: 100 }));
    tick(2000);
    fixture.detectChanges();

    expect(component.job()?.status).toBe('COMPLETED');
  }));

  it('renders errors table when errorsPage signal is set', fakeAsync(() => {
    fixture.detectChanges();
    http.expectOne((r) => r.url.includes('/api/admin/decks')).flush(EMPTY_DECK_PAGE);

    // Manually set job + errorsPage signals to simulate post-completion state
    component.job.set(makeJob({ status: 'FAILED', errorCount: 1 }));
    component.errorsPage.set({
      content: [{ lineNo: 5, rawExcerpt: 'a,b,c', reason: 'Too many columns' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    fixture.detectChanges();

    const errTable = fixture.nativeElement.querySelector('app-vocab-import-errors-table');
    expect(errTable).toBeTruthy();
  }));
});
