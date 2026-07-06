import { ChartConfiguration, ChartOptions } from 'chart.js';
import { CHART_COLORS } from './progress-chart-colors';
import { StudySummary, ActivityPoint, ForecastPoint, AccuracyPoint } from '../data/progress.model';

// chart.js's strict per-type generics make sharing option objects across chart types awkward
// (e.g. doughnut-only `cutout`). The config layer is internal, so we type it with `any` to keep
// the builders readable; the runtime shapes are valid chart.js configs.
type AnyChartConfig = ChartConfiguration<any>;

/** 'YYYY-MM-DD' → 'DD/MM' for compact x-axis labels. */
function shortDate(iso: string): string {
  const parts = iso.split('-');
  return parts.length === 3 ? `${parts[2]}/${parts[1]}` : iso;
}

/** Shared cartesian axis options (faint grid, muted ticks, integer y). */
function cartesianOptions(stacked = false): ChartOptions<any> {
  return {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'index', intersect: false },
    plugins: {
      legend: { labels: { color: CHART_COLORS.text, boxWidth: 12, font: { size: 11 } } },
    },
    scales: {
      x: { stacked, grid: { display: false }, ticks: { color: CHART_COLORS.text, font: { size: 10 } } },
      y: {
        stacked,
        beginAtZero: true,
        grid: { color: CHART_COLORS.grid },
        ticks: { color: CHART_COLORS.text, precision: 0, font: { size: 10 } },
      },
    },
  };
}

/** Doughnut of card mastery (NEW / LEARNING / REVIEW). */
export function masteryConfig(s: StudySummary): AnyChartConfig {
  return {
    type: 'doughnut',
    data: {
      labels: ['Mới', 'Đang học', 'Thuộc'],
      datasets: [
        {
          data: [s.newCount, s.learningCount, s.reviewCount],
          backgroundColor: [CHART_COLORS.slate, CHART_COLORS.amber, CHART_COLORS.green],
          borderWidth: 0,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '62%',
      plugins: { legend: { position: 'bottom', labels: { color: CHART_COLORS.text, boxWidth: 12, font: { size: 11 } } } },
    },
  };
}

/** Line chart: reviews vs new cards per day. */
export function activityConfig(points: ActivityPoint[]): AnyChartConfig {
  return {
    type: 'line',
    data: {
      labels: points.map((p) => shortDate(p.date)),
      datasets: [
        {
          label: 'Ôn tập',
          data: points.map((p) => p.reviews),
          borderColor: CHART_COLORS.indigo,
          backgroundColor: CHART_COLORS.indigo,
          tension: 0.3,
          pointRadius: 2,
        },
        {
          label: 'Từ mới',
          data: points.map((p) => p.newCards),
          borderColor: CHART_COLORS.violet,
          backgroundColor: CHART_COLORS.violet,
          tension: 0.3,
          pointRadius: 2,
        },
      ],
    },
    options: cartesianOptions(),
  };
}

/** Bar chart: cards due per upcoming day. */
export function forecastConfig(points: ForecastPoint[]): AnyChartConfig {
  return {
    type: 'bar',
    data: {
      labels: points.map((p) => shortDate(p.date)),
      datasets: [
        {
          label: 'Đến hạn',
          data: points.map((p) => p.dueCount),
          backgroundColor: CHART_COLORS.indigo,
          borderRadius: 4,
        },
      ],
    },
    options: { ...cartesianOptions(), plugins: { legend: { display: false } } },
  };
}

/** Stacked bar: per-day rating breakdown. */
export function accuracyConfig(points: AccuracyPoint[]): AnyChartConfig {
  return {
    type: 'bar',
    data: {
      labels: points.map((p) => shortDate(p.date)),
      datasets: [
        { label: 'Quên', data: points.map((p) => p.again), backgroundColor: CHART_COLORS.red },
        { label: 'Khó', data: points.map((p) => p.hard), backgroundColor: CHART_COLORS.amber },
        { label: 'Tốt', data: points.map((p) => p.good), backgroundColor: CHART_COLORS.indigo },
        { label: 'Dễ', data: points.map((p) => p.easy), backgroundColor: CHART_COLORS.green },
      ],
    },
    options: cartesianOptions(true),
  };
}

/** True when every point in a series is zero — used to show an empty-state hint. */
export function allZero(values: number[]): boolean {
  return values.every((v) => v === 0);
}
