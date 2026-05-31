import { Component, OnInit, DestroyRef, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, of, switchMap } from 'rxjs';
import { VerbApiService } from './data/verb-api.service';
import { VerbResponse } from './data/verb.model';

/**
 * Verb detail page: reads :baseForm route param, fetches single verb,
 * renders full info. Shows friendly message on 404.
 */
@Component({
  selector: 'app-verb-detail-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './verb-detail-page.component.html',
})
export class VerbDetailPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly verbApi = inject(VerbApiService);
  // H2: DestroyRef-based cleanup — avoids OnDestroy boilerplate, works with dynamic insertion
  private readonly destroyRef = inject(DestroyRef);

  readonly verb = signal<VerbResponse | null>(null);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.route.paramMap.pipe(
      switchMap((params) => {
        const baseForm = params.get('baseForm') ?? '';
        this.loading.set(true);
        this.notFound.set(false);
        this.error.set(null);
        return this.verbApi.getByBaseForm(baseForm).pipe(
          catchError((err) => {
            if (err?.status === 404) {
              this.notFound.set(true);
            } else {
              this.error.set(err?.error?.message ?? 'Failed to load verb details.');
            }
            return of(null);
          })
        );
      }),
      // H2: unsubscribe automatically when component is destroyed
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((res) => {
      this.loading.set(false);
      this.verb.set(res);
    });
  }
}
