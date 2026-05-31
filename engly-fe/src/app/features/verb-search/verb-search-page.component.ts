import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, switchMap, catchError, tap } from 'rxjs/operators';
import { of, Subscription } from 'rxjs';
import { VerbApiService } from './data/verb-api.service';
import { VerbResponse } from './data/verb.model';

/**
 * Verb search page: debounced search box + paginated results table.
 * State managed via Angular signals. Initial load shows all verbs (empty q).
 */
@Component({
  selector: 'app-verb-search-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './verb-search-page.component.html',
})
export class VerbSearchPageComponent implements OnInit, OnDestroy {
  private readonly verbApi = inject(VerbApiService);

  readonly searchControl = new FormControl('', { nonNullable: true });

  readonly results = signal<VerbResponse[]>([]);
  // L3: initialize true so the initial load doesn't flash "No verbs found" before first response
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly totalElements = signal(0);

  private subscription?: Subscription;

  ngOnInit(): void {
    this.subscription = this.searchControl.valueChanges.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      tap(() => {
        this.loading.set(true);
        this.error.set(null);
      }),
      switchMap((q) =>
        this.verbApi.search(q).pipe(
          catchError((err) => {
            this.error.set(err?.error?.message ?? 'Failed to load verbs. Please try again.');
            return of(null);
          })
        )
      )
    ).subscribe((res) => {
      this.loading.set(false);
      if (res) {
        this.results.set(res.content);
        this.totalElements.set(res.totalElements);
      }
    });

    // Trigger initial load with empty query to show all verbs
    this.searchControl.setValue('');
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }
}
