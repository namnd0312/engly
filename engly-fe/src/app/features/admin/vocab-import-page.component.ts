import { Component, OnInit, DestroyRef, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { interval } from 'rxjs';
import { switchMap, takeWhile, catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import { AdminDeckApiService } from './data/admin-deck-api.service';
import { VocabImportApiService } from './data/vocab-import-api.service';
import { TusUploaderService, TusUploadHandle } from './data/tus-uploader.service';
import { AdminDeck } from './data/admin-deck.model';
import { ImportJob, ImportErrorPage } from './data/vocab-import.model';
import { VocabImportErrorsTableComponent } from './vocab-import-errors-table.component';

/**
 * Admin page: pick deck → tus resumable upload (progress) → create import job
 * → poll progress → view counts/percent → retry on FAILED → paginated errors table.
 */
@Component({
  selector: 'app-vocab-import-page',
  standalone: true,
  imports: [VocabImportErrorsTableComponent],
  templateUrl: './vocab-import-page.component.html',
})
export class VocabImportPageComponent implements OnInit {
  private readonly deckApi = inject(AdminDeckApiService);
  private readonly importApi = inject(VocabImportApiService);
  private readonly tusUploader = inject(TusUploaderService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly decks = signal<AdminDeck[]>([]);
  readonly selectedDeckId = signal<number | null>(null);
  readonly file = signal<File | null>(null);
  readonly uploadProgress = signal(0);
  readonly uploading = signal(false);
  readonly creating = signal(false);
  readonly job = signal<ImportJob | null>(null);
  readonly polling = signal(false);
  readonly errorsPage = signal<ImportErrorPage | null>(null);
  readonly serverError = signal<string | null>(null);

  private activeUpload: TusUploadHandle | null = null;
  private currentJobId: number | null = null;

  ngOnInit(): void {
    this.deckApi
      .list('', 0, 100)
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (res) this.decks.set(res.content);
      });
  }

  onDeckChange(event: Event): void {
    const val = (event.target as HTMLSelectElement).value;
    this.selectedDeckId.set(val ? Number(val) : null);
  }

  onFileChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
  }

  startUpload(): void {
    const f = this.file();
    const deckId = this.selectedDeckId();
    if (!f || deckId == null) return;

    this.serverError.set(null);
    this.uploading.set(true);
    this.uploadProgress.set(0);

    this.activeUpload = this.tusUploader.upload(f, {
      onProgress: (pct) => this.uploadProgress.set(pct),
      onSuccess: (uploadId) => {
        this.uploading.set(false);
        this.creating.set(true);
        this.importApi
          .create({ deckId, uploadId })
          .pipe(
            catchError((err) => {
              this.creating.set(false);
              this.serverError.set(err?.error?.message ?? 'Failed to create import job.');
              return of(null);
            }),
            takeUntilDestroyed(this.destroyRef),
          )
          .subscribe((res) => {
            this.creating.set(false);
            if (res) {
              this.currentJobId = res.jobId;
              this.startPolling(res.jobId);
            }
          });
      },
      onError: (err) => {
        this.uploading.set(false);
        this.serverError.set(err.message ?? 'Upload failed.');
      },
    });
  }

  startPolling(id: number): void {
    this.polling.set(true);
    interval(2000)
      .pipe(
        switchMap(() =>
          this.importApi.getJob(id).pipe(catchError(() => of(null))),
        ),
        takeWhile((j) => j !== null && (j.status === 'PENDING' || j.status === 'RUNNING'), true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (j) => {
          if (j) {
            this.job.set(j);
            if (j.status === 'COMPLETED' || j.status === 'FAILED') {
              this.polling.set(false);
              if (j.errorCount > 0) this.loadErrors(0);
            }
          }
        },
        complete: () => this.polling.set(false),
      });
  }

  retry(): void {
    const id = this.currentJobId;
    if (id == null) return;
    this.serverError.set(null);
    this.importApi
      .retry(id)
      .pipe(
        catchError((err) => {
          this.serverError.set(err?.error?.message ?? 'Retry failed.');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (res) this.startPolling(id);
      });
  }

  loadErrors(page: number): void {
    const id = this.currentJobId;
    if (id == null) return;
    this.importApi
      .getErrors(id, page)
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((p) => {
        if (p) this.errorsPage.set(p);
      });
  }

  cancelUpload(): void {
    this.activeUpload?.abort();
    this.activeUpload = null;
    this.uploading.set(false);
    this.uploadProgress.set(0);
  }

  goBack(): void {
    this.router.navigate(['/admin']);
  }
}
