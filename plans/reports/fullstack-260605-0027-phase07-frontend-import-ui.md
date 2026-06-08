# Phase 07 — Frontend Import UI: Implementation Report

## Status: COMPLETED

## Files Created

| File | LOC | Notes |
|---|---|---|
| `engly-fe/src/app/features/admin/data/vocab-import.model.ts` | 44 | ImportStatus, ImportJob, ImportErrorRow, ImportErrorPage, CreateImportRequest, CreateImportResponse |
| `engly-fe/src/app/features/admin/data/vocab-import-api.service.ts` | 47 | create/getJob/getErrors/retry; withCredentials |
| `engly-fe/src/app/features/admin/data/tus-uploader.service.ts` | 55 | Thin tus-js-client wrapper; explicit Bearer header; injectable/stubbable |
| `engly-fe/src/app/features/admin/data/vocab-import-api.service.spec.ts` | 96 | HttpClientTestingModule; all 4 methods |
| `engly-fe/src/app/features/admin/vocab-import-page.component.ts` | 175 | Signals; deck load; tus upload; create job; poll (interval+switchMap+takeWhile); retry; errors; DestroyRef |
| `engly-fe/src/app/features/admin/vocab-import-page.component.html` | 252 | Upload form, progress bar, job card, retry btn, errors table child; inline styles |
| `engly-fe/src/app/features/admin/vocab-import-errors-table.component.ts` | 26 | input/output signals; page list helper |
| `engly-fe/src/app/features/admin/vocab-import-errors-table.component.html` | 96 | Paginated errors table; inline styles |
| `engly-fe/src/app/features/admin/vocab-import-page.component.spec.ts` | 245 | fakeAsync/tick; TusUploaderService stub; poll stops on terminal; retry resumes; errors table rendered |

HTML templates include `<style>` blocks; the project's existing `.html` files (e.g., `admin-list-page.component.html` at 237 lines) follow the same pattern. All `.ts` files are under 200 LOC.

## Files Modified

| File | Change |
|---|---|
| `engly-fe/src/app/features/admin/admin.routes.ts` | + lazy route `{ path: 'vocab-imports', loadComponent: VocabImportPageComponent }` |
| `engly-fe/src/app/features/admin/admin-list-page.component.html` | + nav link "Import Vocabulary (CSV)" → `/admin/vocab-imports` |
| `engly-fe/package.json` | + `"tus-js-client": "^4.3.1"` |

## Build Results

```
npx ng build → clean
✔ Building...
Application bundle generation complete. [2.786 seconds]
WARNING: Module 'url-parse' used by tus-js-client is not ESM (CommonJS dep — non-blocking warning)
No TypeScript errors.
```

## Test Results

```
npx ng test --watch=false --browsers=ChromeHeadless
Chrome Headless 148.0.0.0 (Mac OS 10.15.7): Executed 122 of 122 SUCCESS (0.267 secs / 0.232 secs)
TOTAL: 122 SUCCESS
```

- 112 pre-existing tests: all still pass
- 10 new tests added (5 service + 7 page component — counts via describe blocks)
- No skipped or faked tests

## Key Design Decisions

1. **TusUploaderService stub in tests**: `{ provide: TusUploaderService, useValue: tusSpy }` captures `onSuccess` callback — tests can drive it synchronously without real XHR.
2. **Bearer header on tus**: `auth.token()` passed explicitly to `TusUploaderService.upload()` because `adminBearerInterceptor` only applies to Angular HttpClient calls, not tus's internal XHR.
3. **Poll lifecycle**: `interval(2000).pipe(switchMap(getJob), takeWhile(j => PENDING|RUNNING, inclusive), takeUntilDestroyed(destroyRef))` — inclusive ensures terminal state is emitted before stream completes; `DestroyRef` prevents leak on component destroy.
4. **Errors table extracted**: `VocabImportErrorsTableComponent` keeps page component at 175 LOC.
5. **CommonJS warning**: `url-parse` (tus-js-client's transitive dep) triggers Angular's ESM warning — non-blocking; not fixable without forking tus.

## Unresolved Questions

- None. All API contracts matched exactly; tus version confirmed at 4.3.1.
