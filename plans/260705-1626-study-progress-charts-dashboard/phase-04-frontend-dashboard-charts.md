---
phase: 4
title: Frontend Dashboard + Charts
status: completed
effort: 6h
---

# Phase 4: Frontend Dashboard + Charts

## Overview
Build the `/progress` dashboard: KPI cards + four charts via ng2-charts. Standalone components,
signals, `@if`/`@for` templates, own CSS in the app's indigo/dark palette. Vietnamese copy.

## Requirements
### Functional
- On load, fetch all four stats endpoints in parallel (`forkJoin`); show loading + error states
  (mirror `deck-list-page` loading/error signal pattern).
- **KPI cards**: current streak (🔥 days), total cards, reviewed today, due today.
- **Mastery donut**: NEW / LEARNING / REVIEW counts (from summary).
- **Activity chart** (line or grouped bar, 30d): `reviews` and `newCards` per day (two series).
- **Forecast chart** (bar, 14d): due cards per upcoming day.
- **Accuracy chart** (stacked bar, 30d): again/hard/good/easy per day; optional overall accuracy % label.
- Empty-data friendly: charts render axes with zeros when history is sparse (no crash, a subtle
  "chưa có dữ liệu" hint where all-zero).
### Non-functional
- Each file < 200 LOC → split charts into small presentational child components taking `@Input()` data;
  dashboard page is the orchestrator (fetch + layout). Reuse a shared chart color constant.

## Architecture
### Components
```
src/app/features/progress/
  progress-dashboard-page.component.{ts,html,css}   // orchestrator: fetch (forkJoin), KPI cards, layout
  charts/mastery-donut.component.ts                 // @Input summary → doughnut
  charts/activity-chart.component.ts                // @Input ActivityPoint[] → line/bar (2 series)
  charts/forecast-chart.component.ts                // @Input ForecastPoint[] → bar
  charts/accuracy-chart.component.ts                // @Input AccuracyPoint[] → stacked bar
```
Each chart component: standalone, `imports: [BaseChartDirective]`, exposes `data`/`options`
computed from its `@Input()` (use a setter or `computed()` over an input signal). Template is a
single `<canvas baseChart [type]="..." [data]="..." [options]="...">`.

### chart.js registration
In `app.config.ts` (or each chart component) register needed controllers once:
`Chart.register(...registerables)` — simplest; or selectively register
(DoughnutController, BarController, LineController, CategoryScale, LinearScale, Tooltip, Legend)
to trim bundle. Do it once at bootstrap to avoid "controller not registered" errors.

### Data → chart mapping
- Dates (`ActivityPoint.date` = ISO `YYYY-MM-DD`) → x-axis labels; format short (`DD/MM`).
- Mastery donut: `[newCount, learningCount, reviewCount]`, labels `['Mới','Đang học','Thuộc']`.
- Activity: labels = dates; datasets `[{label:'Ôn tập', data:reviews}, {label:'Từ mới', data:newCards}]`.
- Forecast: labels = dates; dataset `dueCount`.
- Accuracy: labels = dates; 4 stacked datasets `again/hard/good/easy`.
- Colors: indigo `#6366f1` primary; derive a small palette (e.g. green=good/easy, amber=hard,
  red=again) as a shared const `progress-chart-colors.ts`.

### Layout / styling
Responsive card grid (CSS grid, `minmax`), each chart in a titled panel matching existing study
card style (rounded, subtle shadow, `#1a1a2e` dark accents). KPI cards in a top row.

## Related Code Files
### Create
- `engly-fe/src/app/features/progress/progress-dashboard-page.component.ts`
- `engly-fe/src/app/features/progress/progress-dashboard-page.component.html`
- `engly-fe/src/app/features/progress/progress-dashboard-page.component.css`
- `engly-fe/src/app/features/progress/charts/mastery-donut.component.ts`
- `engly-fe/src/app/features/progress/charts/activity-chart.component.ts`
- `engly-fe/src/app/features/progress/charts/forecast-chart.component.ts`
- `engly-fe/src/app/features/progress/charts/accuracy-chart.component.ts`
- `engly-fe/src/app/features/progress/charts/progress-chart-colors.ts`
### Modify
- `engly-fe/src/app/app.config.ts` — register chart.js controllers (if done at bootstrap).
- `engly-fe/src/app/features/progress/progress.routes.ts` — point to the real dashboard component
  (replace P3 shell).
### Delete
- P3 shell placeholder (folded into the real dashboard component).

## Implementation Steps
1. Register chart.js at bootstrap.
2. `progress-chart-colors.ts` shared palette.
3. Four chart child components (each < 80 LOC): `@Input()` data → `computed` chart data/options.
4. Dashboard page: inject `ProgressApiService`; `forkJoin` the 4 calls in `ngOnInit`; store in
   signals; loading/error UI; template renders KPI cards + 4 chart components with `@if` guards.
5. Wire route to real component; remove shell.
6. `ng build` + manual check at `/progress` (logged in).

## Success Criteria
- [ ] `/progress` renders KPI cards + 4 charts with real data for the logged-in user.
- [ ] Snapshot charts (mastery, forecast, newCards line) non-empty immediately; reviews/accuracy
      populate as `review_log` accrues.
- [ ] Loading + error states behave (session-expired path surfaces a re-login CTA like study).
- [ ] `ng build` clean; no chart.js "controller not registered" runtime errors.
- [ ] Each source file < 200 LOC.

## Risk Assessment
- **chart.js registration errors**: register controllers/scales once at bootstrap; document which.
- **Change detection with signals + chart.js**: ng2-charts `BaseChartDirective` updates on input
  ref change — pass NEW array/object refs (don't mutate) so charts redraw. Use `computed()`.
- **SSR/hydration**: app is CSR SPA (no SSR) — canvas is safe.
- **Sparse data UX**: show "chưa có dữ liệu" hint when a series is all-zero to avoid a confusing blank chart.
