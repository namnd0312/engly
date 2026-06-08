import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { StudyApiService } from './data/study-api.service';

/**
 * Study settings page (/study/settings).
 * Loads current SRS settings, allows editing newCardsPerDay and maxReviewsPerDay,
 * saves via PUT /api/study/settings. Blank maxReviewsPerDay = unlimited (null).
 */
@Component({
  selector: 'app-study-settings-page',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './study-settings-page.component.html',
  styleUrl: './study-settings-page.component.css',
})
export class StudySettingsPageComponent implements OnInit, OnDestroy {
  private readonly studyApi = inject(StudyApiService);
  private readonly router = inject(Router);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly saveError = signal<string | null>(null);
  readonly saveSuccess = signal(false);

  /** Bound to form inputs. maxReviewsPerDay empty string = unlimited (null). */
  newCardsPerDay = 20;
  maxReviewsPerDayInput = '';

  private sub?: Subscription;

  ngOnInit(): void {
    this.sub = this.studyApi.getSettings().subscribe({
      next: (s) => {
        this.newCardsPerDay = s.newCardsPerDay;
        this.maxReviewsPerDayInput = s.maxReviewsPerDay != null ? String(s.maxReviewsPerDay) : '';
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(err?.error?.message ?? 'Failed to load settings.');
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  save(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.saveError.set(null);
    this.saveSuccess.set(false);

    const maxReviews =
      this.maxReviewsPerDayInput.trim() === ''
        ? null
        : Number(this.maxReviewsPerDayInput);

    this.studyApi
      .updateSettings({ newCardsPerDay: this.newCardsPerDay, maxReviewsPerDay: maxReviews })
      .subscribe({
        next: (s) => {
          this.newCardsPerDay = s.newCardsPerDay;
          this.maxReviewsPerDayInput = s.maxReviewsPerDay != null ? String(s.maxReviewsPerDay) : '';
          this.saving.set(false);
          this.saveSuccess.set(true);
        },
        error: (err) => {
          this.saveError.set(err?.error?.message ?? 'Failed to save settings.');
          this.saving.set(false);
        },
      });
  }

  backToDecks(): void {
    this.router.navigate(['/study']);
  }
}
