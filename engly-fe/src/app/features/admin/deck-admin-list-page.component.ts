import { Component, OnInit, OnDestroy, DestroyRef, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { SlicePipe } from '@angular/common';
import { debounceTime, distinctUntilChanged, switchMap, catchError, tap } from 'rxjs/operators';
import { of, Subscription } from 'rxjs';
import { AdminDeckApiService } from './data/admin-deck-api.service';
import { AdminDeck } from './data/admin-deck.model';

/**
 * Admin deck list: debounced search + paged table with edit/delete.
 * Mirrors admin-list-page (verb admin) pattern. Signal-based state.
 */
@Component({
  selector: 'app-deck-admin-list-page',
  standalone: true,
  imports: [ReactiveFormsModule, SlicePipe],
  templateUrl: './deck-admin-list-page.component.html',
})
export class DeckAdminListPageComponent implements OnInit, OnDestroy {
  private readonly api = inject(AdminDeckApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly searchControl = new FormControl('', { nonNullable: true });

  readonly decks = signal<AdminDeck[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly totalElements = signal(0);

  private subscription?: Subscription;

  ngOnInit(): void {
    this.subscription = this.searchControl.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap(() => {
          this.loading.set(true);
          this.error.set(null);
        }),
        switchMap((q) =>
          this.api.list(q).pipe(
            catchError((err) => {
              this.error.set(err?.error?.message ?? 'Failed to load decks. Please try again.');
              return of(null);
            }),
          ),
        ),
      )
      .subscribe((res) => {
        this.loading.set(false);
        if (res) {
          this.decks.set(res.content);
          this.totalElements.set(res.totalElements);
        }
      });

    // Initial load.
    this.searchControl.setValue('');
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  private reload(): void {
    const q = this.searchControl.value;
    this.loading.set(true);
    this.api
      .list(q)
      .pipe(
        catchError((err) => {
          this.error.set(err?.error?.message ?? 'Failed to load decks. Please try again.');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        this.loading.set(false);
        if (res) {
          this.decks.set(res.content);
          this.totalElements.set(res.totalElements);
        }
      });
  }

  onDelete(deck: AdminDeck): void {
    if (!confirm(`Delete deck "${deck.name}"? This cannot be undone.`)) return;
    this.api
      .delete(deck.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.reload(),
        error: (err) => this.error.set(err?.error?.message ?? 'Delete failed.'),
      });
  }

  goNew(): void {
    this.router.navigate(['/admin/decks/new']);
  }

  goEdit(id: number): void {
    this.router.navigate(['/admin/decks', id, 'edit']);
  }

  goBack(): void {
    this.router.navigate(['/admin']);
  }
}
