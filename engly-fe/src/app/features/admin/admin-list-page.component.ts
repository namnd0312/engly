import { Component, OnInit, OnDestroy, DestroyRef, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, switchMap, catchError, tap } from 'rxjs/operators';
import { of, Subscription } from 'rxjs';
import { AdminVerbApiService } from './data/admin-verb-api.service';
import { VerbAdminResponse } from './data/admin-verb.model';

/**
 * Admin verb list: debounced search + table with edit/delete and an inline verified toggle.
 * Signal-based state, mirroring the public verb-search page. Reachable only via the admin guard.
 */
@Component({
  selector: 'app-admin-list-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './admin-list-page.component.html',
})
export class AdminListPageComponent implements OnInit, OnDestroy {
  private readonly api = inject(AdminVerbApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly searchControl = new FormControl('', { nonNullable: true });

  readonly verbs = signal<VerbAdminResponse[]>([]);
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
              this.error.set(err?.error?.message ?? 'Failed to load verbs. Please try again.');
              return of(null);
            }),
          ),
        ),
      )
      .subscribe((res) => {
        this.loading.set(false);
        if (res) {
          this.verbs.set(res.content);
          this.totalElements.set(res.totalElements);
        }
      });

    // Initial load (empty query).
    this.searchControl.setValue('');
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  /** Re-run the current search (used after delete/toggle). */
  private reload(): void {
    const q = this.searchControl.value;
    this.loading.set(true);
    this.api
      .list(q)
      .pipe(
        catchError((err) => {
          this.error.set(err?.error?.message ?? 'Failed to load verbs. Please try again.');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        this.loading.set(false);
        if (res) {
          this.verbs.set(res.content);
          this.totalElements.set(res.totalElements);
        }
      });
  }

  onDelete(verb: VerbAdminResponse): void {
    if (!confirm(`Delete verb "${verb.baseForm}"? This cannot be undone.`)) {
      return;
    }
    this.api
      .delete(verb.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.reload(),
        error: (err) => this.error.set(err?.error?.message ?? 'Delete failed.'),
      });
  }

  onToggle(verb: VerbAdminResponse): void {
    this.api
      .toggleVerified(verb.id, !verb.verified)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) =>
          this.verbs.update((list) => list.map((v) => (v.id === updated.id ? updated : v))),
        error: (err) => this.error.set(err?.error?.message ?? 'Update failed.'),
      });
  }

  goNew(): void {
    this.router.navigate(['/admin/verbs/new']);
  }

  goEdit(id: number): void {
    this.router.navigate(['/admin/verbs', id, 'edit']);
  }
}
