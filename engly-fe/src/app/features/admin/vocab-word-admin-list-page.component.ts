import { Component, OnInit, OnDestroy, DestroyRef, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { debounceTime, distinctUntilChanged, switchMap, catchError, tap } from 'rxjs/operators';
import { of, Subscription } from 'rxjs';
import { AdminVocabWordApiService } from './data/admin-vocab-word-api.service';
import { AdminDeckApiService } from './data/admin-deck-api.service';
import { AdminVocabWord } from './data/admin-vocab-word.model';
import { AdminDeck } from './data/admin-deck.model';

/**
 * Admin vocab-word list: debounced search, deck filter dropdown, verified toggle,
 * edit/delete actions. Mirrors verb admin list pattern with signal-based state.
 */
@Component({
  selector: 'app-vocab-word-admin-list-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './vocab-word-admin-list-page.component.html',
})
export class VocabWordAdminListPageComponent implements OnInit, OnDestroy {
  private readonly api = inject(AdminVocabWordApiService);
  private readonly deckApi = inject(AdminDeckApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly searchControl = new FormControl('', { nonNullable: true });
  readonly deckFilterControl = new FormControl<number | null>(null);

  readonly words = signal<AdminVocabWord[]>([]);
  readonly decks = signal<AdminDeck[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly totalElements = signal(0);

  private subscription?: Subscription;

  ngOnInit(): void {
    // Load deck options for filter dropdown.
    this.deckApi.list('', 0, 200).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (res) => this.decks.set(res.content),
    });

    this.subscription = this.searchControl.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap(() => { this.loading.set(true); this.error.set(null); }),
        switchMap((q) => this.fetchWords(q)),
      )
      .subscribe((res) => {
        this.loading.set(false);
        if (res) { this.words.set(res.content); this.totalElements.set(res.totalElements); }
      });

    this.searchControl.setValue('');
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  onDeckFilterChange(): void {
    this.reload();
  }

  private fetchWords(q: string) {
    const deckId = this.deckFilterControl.value ?? undefined;
    return this.api.list(q, 0, 20, deckId).pipe(
      catchError((err) => {
        this.error.set(err?.error?.message ?? 'Failed to load words. Please try again.');
        return of(null);
      }),
    );
  }

  private reload(): void {
    const q = this.searchControl.value;
    this.loading.set(true);
    this.fetchWords(q)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((res) => {
        this.loading.set(false);
        if (res) { this.words.set(res.content); this.totalElements.set(res.totalElements); }
      });
  }

  onDelete(word: AdminVocabWord): void {
    if (!confirm(`Delete word "${word.term}"? This cannot be undone.`)) return;
    this.api.delete(word.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.reload(),
      error: (err) => this.error.set(err?.error?.message ?? 'Delete failed.'),
    });
  }

  onToggleVerified(word: AdminVocabWord): void {
    this.api.toggleVerified(word.id, !word.verified)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) =>
          this.words.update((list) => list.map((w) => (w.id === updated.id ? updated : w))),
        error: (err) => this.error.set(err?.error?.message ?? 'Update failed.'),
      });
  }

  /** Return deck name by id for display in table. */
  deckName(deckId: number): string {
    return this.decks().find((d) => d.id === deckId)?.name ?? String(deckId);
  }

  goNew(): void { this.router.navigate(['/admin/vocab-words/new']); }
  goEdit(id: number): void { this.router.navigate(['/admin/vocab-words', id, 'edit']); }
  goBack(): void { this.router.navigate(['/admin']); }
}
