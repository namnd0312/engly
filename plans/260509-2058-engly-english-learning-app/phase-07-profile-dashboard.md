# Phase 07 — Profile, Settings & Dashboard

## Context Links
- Parent: [plan.md](./plan.md)
- Prev: [phase-06-gamification.md](./phase-06-gamification.md)
- Next: [phase-08-testing-hardening-docs.md](./phase-08-testing-hardening-docs.md)
- Depends on: phases 03–06 endpoints

## Overview
- **Date:** 2026-05-09
- **Priority:** P2
- **Status:** pending
- **Effort:** 8h
- **Description:** `/me` profile + settings endpoints, aggregated stats, 90-day activity heatmap. FE dashboard composes streak flame, XP bar, daily-goal progress, word-of-the-day, weak-topics, leaderboard preview, heatmap.

## Key Insights
- Stats endpoint aggregates from existing tables — no new schema beyond what phase-06 added.
- Heatmap input: 90 contiguous dates each with `xp_earned` + `goal_met`. Missing days = 0.
- IANA timezone validation via `ZoneId.of(...)` (throws on invalid).
- Avatar: URL pasted (no upload service); validate https + image extension or known providers (gravatar/cdn).

## Requirements
**Functional**
- `GET /api/v1/me`: profile (id, email, displayName, avatarUrl, cefrSelfRating, timezone, dailyGoalXp, role, emailVerified, createdAt).
- `PATCH /api/v1/me` body subset: `{displayName?, avatarUrl?, cefrSelfRating?, timezone?, dailyGoalXp?}`.
- `POST /api/v1/me/change-password` `{currentPassword, newPassword}`.
- `GET /api/v1/me/stats`: `{totalWordsLearned, totalReviews, averageAccuracy, currentStreak, longestStreak, totalXp, level, last90Days: [{date, xpEarned, goalMet}]}`.
- `DELETE /api/v1/me`: GDPR delete (cascades).

**Non-functional**
- `/me/stats` ≤300ms p95 (single round-trip aggregation).
- All updates revoke refresh tokens on password change (already in phase-03).

## Architecture
```mermaid
flowchart LR
  Dashboard --> S[/me/stats]
  Dashboard --> P[/me/progress]
  Dashboard --> A[/me/achievements]
  Dashboard --> WT[/me/weak-topics]
  Dashboard --> WOD[/words/word-of-the-day]
  Dashboard --> LB[/leaderboard?scope=weekly&limit=10]
```

## Related Code Files
**Backend `com.engly.user`**
- `controller/MeController.java` — GET/PATCH/DELETE /me, change-password.
- `controller/MeStatsController.java` — GET /me/stats.
- `service/MeService.java`, `service/StatsAggregationService.java`.
- `dto/UserProfileDto.java`, `UpdateProfileRequest.java`, `ChangePasswordRequest.java`, `UserStatsDto.java`, `DailyHeatCellDto.java`.
- `mapper/UserProfileMapper.java`.
- `validator/IanaTimezoneValidator.java` (`@ValidTimezone` ConstraintValidator).
- `validator/HttpsUrlValidator.java` (`@ValidHttpsUrl`).

**Frontend**
- `app/dashboard/page.tsx` — composed grid of widgets.
- `app/profile/page.tsx` — display profile w/ edit button.
- `app/profile/edit/page.tsx` — form.
- `app/profile/settings/page.tsx` — password, timezone, daily goal, account delete.
- `components/features/dashboard/heatmap-90-days.tsx` (uses `react-calendar-heatmap`).
- `components/features/dashboard/dashboard-grid.tsx`.
- `components/features/dashboard/weak-topics-card.tsx`.
- `components/features/dashboard/leaderboard-preview-card.tsx`.
- `components/features/dashboard/daily-goal-progress.tsx`.
- `components/features/profile/profile-edit-form.tsx`, `change-password-form.tsx`, `delete-account-dialog.tsx`.
- `hooks/use-me.ts`, `use-stats.ts`.
- `lib/validation/profile-schemas.ts`.

## Implementation Steps
1. `IanaTimezoneValidator.isValid(String)` → `try { ZoneId.of(s); return true; } catch (...) { return false; }`. Annotate `@ValidTimezone` for `UpdateProfileRequest.timezone`.
2. `HttpsUrlValidator`: regex `^https://.+\\.(png|jpg|jpeg|gif|webp|svg)(\\?.*)?$` OR allowlist host `gravatar.com`, `cdn.engly.com`. Document permissive policy for MVP.
3. `MeService.getProfile(UUID userId)` → load User + map to UserProfileDto.
4. `MeService.updateProfile(UUID userId, UpdateProfileRequest req)` — null-safe partial update; only fields present in request applied.
5. `MeService.changePassword(UUID userId, currentPwd, newPwd)`:
   - Verify current pwd.
   - Hash new with BCrypt strength 12.
   - Save.
   - `RefreshTokenService.revokeAll(userId)` (force re-login on other devices).
6. `MeService.deleteAccount(UUID userId)`:
   - Cascades wipe all FK-related rows.
   - Delete `leaderboard:xp:alltime` ZREM userId; iterate weekly keys (or skip — they expire). Document.
   - Mail "account deleted" confirmation (optional, deferred).
7. `StatsAggregationService.compute(UUID userId)`:
   - Query 1: `count(srs_cards) WHERE user_id=? AND state='REVIEW'` → words learned.
   - Query 2: `count(attempts) WHERE user_id=?` and `sum(case when is_correct then 1 else 0)` → total + accuracy.
   - Query 3: `user_progress` → streak + xp + level.
   - Query 4: `daily_activity WHERE user_id=? AND activity_date >= today_user_tz - 89` ORDER BY date.
   - Build `last90Days` array — fill missing dates with `{xpEarned:0, goalMet:false}`.
8. **MeController endpoints**, all `@PreAuthorize("isAuthenticated()")` (default since SecurityConfig).
9. **FE `/dashboard/page.tsx`** layout (CSS grid):
   - Row 1 (2 cols): StreakFlame + XP/Level + DailyGoalProgress (one card), Heatmap90Days.
   - Row 2 (3 cols): WordOfTheDayCard, WeakTopicsCard, LeaderboardPreviewCard.
   - Row 3: AchievementGrid (or "View all" link).
10. **Heatmap component**: `react-calendar-heatmap` with `startDate = today - 89`, `endDate = today`, value `{ date, count: xpEarned }`. classForValue based on quartiles.
11. **Profile page**: shows fields read-only with "Edit" button → `/profile/edit`.
12. **Edit form**: react-hook-form + Zod, optimistic update via TanStack `useMutation` w/ `onMutate` snapshot.
13. **Settings page**: tabs (Account, Password, Notifications [stub], Danger Zone). Delete with double-confirm dialog.
14. **Change-password form**: 2 fields + confirm. On success: log out via `signOut()`.
15. **Timezone select**: shadcn `Combobox` populated from `Intl.supportedValuesOf("timeZone")`.
16. **Daily-goal slider**: shadcn `Slider` 10–500 step 10 (default 50).
17. **CEFR self-rating select**: A1/A2/B1/B2/C1/C2.
18. Smoke test: signup new user → set timezone "Asia/Saigon" → review 5 cards → check `/me/stats` returns proper streak + xp + heatmap with today's xp.

## Todo List
- [ ] IanaTimezoneValidator + annotation
- [ ] HttpsUrlValidator + annotation
- [ ] MeService get/update/change-password/delete
- [ ] StatsAggregationService 4-query path
- [ ] Controllers + DTOs + mappers
- [ ] FE dashboard grid composition
- [ ] FE heatmap-90-days
- [ ] FE weak-topics card
- [ ] FE leaderboard preview card
- [ ] FE daily-goal progress
- [ ] FE profile + edit pages
- [ ] FE settings (account/password/danger)
- [ ] FE delete-account dialog with double-confirm

## Success Criteria
- New user dashboard renders with all widgets, no 500s.
- Update timezone Asia/Saigon → review tomorrow → streak=2 (TZ-correct).
- Password change forces logout on other devices (refresh tokens revoked).
- Delete account: subsequent login attempt fails (user gone).
- Heatmap colors reflect XP intensity quartiles.

## Risk Assessment
- **Risk:** Stats query slow with many attempts → **Mitigation:** indexes already in phase-02; explain plan in phase-08 hardening.
- **Risk:** Timezone abuse (set to far-future TZ to break streaks) → **Mitigation:** all valid IANA strings allowed; lazy-on-read math is correct regardless. No-op risk.
- **Risk:** Account-delete leaves orphan Redis keys (alltime ZSET) → **Mitigation:** explicit ZREM in delete path; weekly keys expire naturally.
- **Risk:** Avatar URL XSS via SVG → **Mitigation:** FE renders via `<img>` with `referrerPolicy="no-referrer"`; deny `data:` and `javascript:` schemes via validator.

## Security Considerations
- Email is read-only post-signup (changes require new verification flow — defer).
- All `/me/*` endpoints require auth; ownership implicit via JWT subject.
- Change-password requires current pwd + revokes all refresh.
- Delete is permanent; FE shows "type DELETE" confirmation.
- PATCH ignores unknown fields (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` returns 400).

## Next Steps
[phase-08-testing-hardening-docs.md](./phase-08-testing-hardening-docs.md) — full coverage, hardening, docs.
