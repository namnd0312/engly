---
phase: 3
title: Frontend Data + Charting Deps
status: completed
effort: 3h
---

# Phase 3: Frontend Data + Charting Deps

## Overview
Install the charting stack and scaffold `features/progress/`: API service, TS models mirroring
P2 DTOs, lazy route at `/progress` (authGuard), and a header nav link. No charts drawn yet (P4).

## Requirements
### Functional
- `ProgressApiService` calls the four `/api/study/stats/**` endpoints with `withCredentials`.
- `/progress` route lazy-loaded, guarded by `authGuard` (mirrors `/study`).
- Header shows a "Tiến độ" (Progress) nav link, visible when authenticated (match how the
  "Học" / study link is conditionally shown in the header component).
### Non-functional
- Follow the exact `study-api.service.ts` pattern (base string, `inject(HttpClient)`, Observables).
- Charting deps compatible with Angular 19 standalone.

## Architecture
### Dependencies to add (engly-fe)
```
npm i chart.js ng2-charts
```
`ng2-charts` v6+ targets Angular 19 and exposes the standalone `BaseChartDirective` (imported
directly into a component's `imports: []` — no NgModule). Verify installed major matches
Angular 19 at install time (`npm i chart.js ng2-charts` then `ng build` smoke).

### Files
```
src/app/features/progress/
  data/progress-api.service.ts        // /api/study/stats/**  (mirror study-api.service.ts)
  data/progress.model.ts              // interfaces
  data/progress-api.service.spec.ts   // (P5)
  progress.routes.ts                  // path '' → loadComponent dashboard
```
### Models (`progress.model.ts`) — mirror P2 DTOs
```ts
export interface StudySummary {
  totalCards: number; newCount: number; learningCount: number; reviewCount: number;
  totalReviews: number; reviewedToday: number; dueToday: number; currentStreakDays: number;
}
export interface ActivityPoint { date: string; reviews: number; newCards: number; }
export interface ForecastPoint { date: string; dueCount: number; }
export interface AccuracyPoint { date: string; again: number; hard: number; good: number; easy: number; }
```
### Service (`progress-api.service.ts`) — pattern-copy of `study-api.service.ts`
`@Injectable({providedIn:'root'})`, `base='/api/study/stats'`, methods:
`getSummary()`, `getActivity(days=30)`, `getForecast(days=14)`, `getAccuracy(days=30)` — each
`http.get<T>(url, { withCredentials: true })`; days passed as `?days=` query param.

### Routing
- `progress.routes.ts`: `[{ path: '', loadComponent: () => import('./progress-dashboard-page.component').then(m => m.ProgressDashboardPageComponent) }]`.
- `app.routes.ts`: add `{ path: 'progress', canActivate: [authGuard], loadChildren: () => import('./features/progress/progress.routes').then(m => m.progressRoutes) }`.
- Header nav: add link to `/progress` next to the existing study link (same auth-conditional).

## Related Code Files
### Create
- `engly-fe/src/app/features/progress/data/progress-api.service.ts`
- `engly-fe/src/app/features/progress/data/progress.model.ts`
- `engly-fe/src/app/features/progress/progress.routes.ts`
### Modify
- `engly-fe/package.json` (+ `package-lock.json`) — add `chart.js`, `ng2-charts`.
- `engly-fe/src/app/app.routes.ts` — add `/progress` route.
- Header/nav component (the one rendering the "Học"/study link — likely `app.component.html`
  or a nav component) — add "Tiến độ" link.
### Delete
- None.

## Implementation Steps
1. `npm i chart.js ng2-charts` in `engly-fe`; confirm versions resolve for Angular 19; `ng build` smoke.
2. `progress.model.ts` interfaces (above).
3. `progress-api.service.ts` (copy study-api pattern; base `/api/study/stats`; 4 GETs with `days`).
4. `progress.routes.ts` → loadComponent (placeholder dashboard created in P4; to compile now,
   either create P4's component shell here or land P3+P4 together). Recommended: create a minimal
   dashboard shell component in P3 so the route compiles, flesh out charts in P4.
5. `app.routes.ts` route + header nav link.
6. `ng build` (or `ng test --watch=false`) smoke.

## Success Criteria
- [ ] `chart.js` + `ng2-charts` installed; `ng build` succeeds.
- [ ] Navigating to `/progress` while logged in loads the (shell) page; anonymous → redirected to login.
- [ ] `ProgressApiService` hits the four endpoints with `withCredentials:true` (P5 spec asserts).

## Risk Assessment
- **ng2-charts / Angular 19 version drift**: pin the Angular-19-compatible major; if incompatible,
  fall back to importing `chart.js` directly and wrapping a `<canvas>` in a tiny directive. Decide
  at install; document the chosen version in `package.json`.
- **Route compile ordering**: `/progress` route needs a real component to `loadComponent`; create
  the dashboard shell in this phase to avoid a broken build between P3 and P4.
- **Bundle size**: chart.js ~drops in; acceptable per locked decision. Tree-shake by importing only
  needed chart types if using `Chart.register(...)` (P4).
