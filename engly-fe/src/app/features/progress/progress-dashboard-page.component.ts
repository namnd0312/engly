import { Component, OnInit, OnDestroy, computed, inject, signal } from '@angular/core';
import { Subscription, forkJoin } from 'rxjs';
import { ProgressApiService } from './data/progress-api.service';
import { StudySummary, ActivityPoint, ForecastPoint, AccuracyPoint } from './data/progress.model';
import { ChartCanvasComponent } from './charts/chart-canvas.component';
import {
  masteryConfig,
  activityConfig,
  forecastConfig,
  accuracyConfig,
  allZero,
} from './charts/progress-chart-configs';

/**
 * Progress dashboard (/progress). Fetches the four stats endpoints in parallel and renders
 * KPI cards + four charts. Snapshot charts (mastery, forecast, new-cards line) populate
 * immediately; review/accuracy series fill in as review_log accrues.
 */
@Component({
  selector: 'app-progress-dashboard-page',
  standalone: true,
  imports: [ChartCanvasComponent],
  templateUrl: './progress-dashboard-page.component.html',
  styleUrl: './progress-dashboard-page.component.css',
})
export class ProgressDashboardPageComponent implements OnInit, OnDestroy {
  private readonly api = inject(ProgressApiService);

  readonly summary = signal<StudySummary | null>(null);
  readonly activity = signal<ActivityPoint[]>([]);
  readonly forecast = signal<ForecastPoint[]>([]);
  readonly accuracy = signal<AccuracyPoint[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  // Chart configs recomputed whenever their source signal changes (new object ref → canvas redraws).
  readonly masteryChart = computed(() => {
    const s = this.summary();
    return s ? masteryConfig(s) : null;
  });
  readonly activityChart = computed(() => activityConfig(this.activity()));
  readonly forecastChart = computed(() => forecastConfig(this.forecast()));
  readonly accuracyChart = computed(() => accuracyConfig(this.accuracy()));

  // Empty-state flags — avoid a confusing all-zero chart.
  readonly masteryEmpty = computed(() => {
    const s = this.summary();
    return !s || s.newCount + s.learningCount + s.reviewCount === 0;
  });
  readonly activityEmpty = computed(() =>
    allZero(this.activity().flatMap((p) => [p.reviews, p.newCards])),
  );
  readonly forecastEmpty = computed(() => allZero(this.forecast().map((p) => p.dueCount)));
  readonly accuracyEmpty = computed(() =>
    allZero(this.accuracy().flatMap((p) => [p.again, p.hard, p.good, p.easy])),
  );

  private sub?: Subscription;

  ngOnInit(): void {
    this.sub = forkJoin({
      summary: this.api.getSummary(),
      activity: this.api.getActivity(30),
      forecast: this.api.getForecast(14),
      accuracy: this.api.getAccuracy(30),
    }).subscribe({
      next: (data) => {
        this.summary.set(data.summary);
        this.activity.set(data.activity);
        this.forecast.set(data.forecast);
        this.accuracy.set(data.accuracy);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Không tải được dữ liệu tiến độ. Vui lòng thử lại.');
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
