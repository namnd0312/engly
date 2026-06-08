# Phase 07 — Frontend Import UI (api service, model, import page, poll, errors, routes)

## Context Links
- [plan.md](plan.md) · [scout §10-17 FE patterns](scout/scout-01-vocab-and-admin-patterns.md) · phase-06 (API contract), phase-02 (tus endpoint)
- Depends on: phase-06 (DTOs), phase-02 (upload endpoint). Can start once API contract frozen.

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 · **Status:** pending
- Angular 19.2 standalone admin UI: pick deck, resumable tus upload w/ progress bar, create job, poll progress, view paginated errors, retry FAILED. Mirrors existing `features/admin` patterns (signals, inject, lazy routes, adminGuard, adminBearerInterceptor).

## Key Insights
- Reuse: `AdminDeckApiService` for deck dropdown; `adminBearerInterceptor` auto-attaches Bearer for `/api/admin`; `adminGuard` already gates admin routes.
- tus-js-client attaches its own PATCH/POST; the interceptor adds Bearer (tus endpoint is under `/api/admin`). Confirm tus-js-client lets Angular HttpClient interceptors apply — it uses XHR/fetch directly, NOT Angular HttpClient → interceptor will NOT run. SOLUTION: pass `Authorization` header explicitly to tus `Upload({ headers: { Authorization: 'Bearer '+token } })` from `AuthService.token()`.
- Standalone components, signals for state (`status`, `progress`, `uploading`, `serverError`, `job`, `errorsPage`), reactive form for deck select.
- Poll with `interval(2000)` + `switchMap(getJob)`; `takeWhile(status not in [COMPLETED,FAILED], inclusive)`; stop on terminal; surface retry button if FAILED.
- Add `tus-js-client` dependency (assume `^4.x`; confirm — OPEN QUESTION #1).

## Requirements
- **Functional:** Upload page (deck select + file picker + upload progress + create job); Progress view (percent bar + counts + retry); Errors view (paginated table).
- **Non-functional:** No memory blowup in browser (tus streams file in chunks); poll stops on terminal state (no leak).

## Architecture
```
features/admin/vocab-imports/
  data/vocab-import-api.service.ts   create/getJob/getErrors/retry (HttpClient, withCredentials)
  data/vocab-import.model.ts         ImportJob, ImportErrorPage, CreateImportRequest, ImportStatus
  vocab-import-page.component.ts/html  deck select → tus upload (progress) → create job → poll → counts/percent → retry; errors table
admin.routes.ts: + 'vocab-imports' (and maybe 'vocab-imports/:id')
```
Keep ONE page component (KISS): upload + progress + errors as sections toggled by job state. Split errors table into a child component only if parent >200 LOC.

## Related Code Files
- **Create:**
  - `engly-fe/src/app/features/admin/data/vocab-import-api.service.ts` (<80 LOC)
  - `engly-fe/src/app/features/admin/data/vocab-import.model.ts` (<40 LOC)
  - `engly-fe/src/app/features/admin/vocab-import-page.component.ts` (<200 LOC; split if needed)
  - `engly-fe/src/app/features/admin/vocab-import-page.component.html`
  - specs: `vocab-import-api.service.spec.ts`, `vocab-import-page.component.spec.ts`
  - (optional) `vocab-import-errors-table.component.ts/html` if parent >200 LOC
- **Modify:**
  - `engly-fe/src/app/features/admin/admin.routes.ts` (+ lazy route `vocab-imports`)
  - `engly-fe/package.json` (+ `tus-js-client`)
  - admin hub list/template (`admin-list-page` or vocab section) → add nav link to import page
- **Delete:** none

## Implementation Steps
1. `npm i tus-js-client` (confirm version). Add `@types` if not bundled (tus-js-client ships types).
2. `vocab-import.model.ts`: `ImportStatus = 'PENDING'|'RUNNING'|'COMPLETED'|'FAILED'`; `ImportJob {id,deckId,status,fileSize,processedCount,insertedCount,skippedCount,errorCount,percent,createdAt}`; `ImportErrorRow {lineNo,rawExcerpt,reason}`; `ImportErrorPage {content,page,size,totalElements,totalPages}`; `CreateImportRequest {deckId,uploadId}`.
3. `vocab-import-api.service.ts` (`providedIn:'root'`, baseUrl `/api/admin/vocab-imports`, `withCredentials:true`):
   - `create(req): Observable<{jobId:number;status:ImportStatus}>`
   - `getJob(id): Observable<ImportJob>`
   - `getErrors(id,page,size): Observable<ImportErrorPage>`
   - `retry(id): Observable<{jobId:number;status:ImportStatus}>`
4. `vocab-import-page.component.ts`:
   - signals: `decks`, `selectedDeckId`, `file`, `uploadProgress`, `uploading`, `creating`, `job`, `polling`, `errorsPage`, `serverError`.
   - on init: `deckApi.list()` → decks.
   - `startUpload()`: build `tus.Upload(file, { endpoint:'/api/admin/vocab-imports/upload', chunkSize, retryDelays, headers:{Authorization:'Bearer '+auth.token()}, onProgress, onSuccess })`; on success extract `uploadId` from `upload.url` last segment → call `create({deckId,uploadId})` → store jobId → `startPolling`.
   - `startPolling(id)`: `interval(2000).pipe(switchMap(()=>api.getJob(id)), takeWhile(j=>j.status==='PENDING'||j.status==='RUNNING', true))` → update `job` signal; on terminal load errors page if errorCount>0.
   - `retry()`: `api.retry(id)` → resume polling.
   - `loadErrors(page)`: `api.getErrors(id,page,size)`.
   - cleanup polling subscription in `ngOnDestroy` (or `takeUntilDestroyed`).
5. Template: deck `<select>`, file input, upload button + progress bar (`uploadProgress`), then job card (status, percent bar, inserted/skipped/error counts), retry button when FAILED, errors `<table>` with pagination when errorCount>0.
6. `admin.routes.ts`: `{ path:'vocab-imports', loadComponent: () => import('./vocab-import-page.component').then(m=>m.VocabImportPageComponent) }`.
7. Add nav link from admin vocab hub.
8. `ng build`/`ng test` clean.

## Todo List
- [ ] tus-js-client dependency (confirm version)
- [ ] model.ts matching phase-06 DTOs exactly
- [ ] api.service.ts (create/getJob/getErrors/retry)
- [ ] page component: deck select + tus upload(progress) + create + poll + counts/percent + retry + errors table
- [ ] tus upload carries Bearer header explicitly (interceptor does NOT apply to tus XHR)
- [ ] poll stops on terminal; subscription cleaned on destroy
- [ ] route + nav link
- [ ] specs; ng build + ng test clean

## Success Criteria
- Upload a CSV with progress bar; interrupt + retry continues (tus resume).
- Job progress bar advances to 100% on COMPLETED; counts shown.
- FAILED job shows retry; clicking resumes.
- Errors table paginates.
- Non-admin cannot reach route (adminGuard).

## Risk Assessment
- **Interceptor not applied to tus requests** → pass Authorization header explicitly to tus Upload (KEY pitfall).
- **Token expiry mid-large-upload** → out of scope now; document; tus retryDelays handle transient network only.
- **Poll leak** → takeUntilDestroyed + terminal takeWhile.
- **Component >200 LOC** → extract errors table child.

## Security Considerations
- adminGuard + adminBearerInterceptor for API calls; explicit Bearer on tus.
- No token persisted beyond AuthService signal.

## Next Steps
- Phase-08 FE specs (service + component) using HttpClientTestingModule + mocked tus. docs-manager updates design-guidelines if new UI pattern.
