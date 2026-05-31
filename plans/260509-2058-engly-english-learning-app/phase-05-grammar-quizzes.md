# Phase 05 — Grammar Exercises & Quizzes

## Context Links
- Parent: [plan.md](./plan.md)
- Prev: [phase-03-authentication.md](./phase-03-authentication.md)
- Parallel-with: [phase-04-vocabulary-srs.md](./phase-04-vocabulary-srs.md)
- Next: [phase-06-gamification.md](./phase-06-gamification.md)
- Docs: [Spring Bean Validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)

## Overview
- **Date:** 2026-05-09
- **Priority:** P1
- **Status:** pending
- **Effort:** 10h
- **Description:** Exercise+Quiz domain, scoring API, attempt history, weak-topic recommendation. Admin CRUD. FE quiz runner (one-question-at-a-time).

## Key Insights
- Three exercise types share one row schema; `options` JSONB empty for fill-in-blank/sentence-construction.
- Scoring is server-side authoritative — never trust client-computed result.
- Weak topic = topic with lowest accuracy (>=5 attempts) over last 30d.
- Sentence-construction normalization: lowercase, collapse whitespace, strip trailing punctuation. Document explicitly to set user expectation.

## Requirements
**Functional**
- `GET /api/v1/exercises?topic=&difficulty=&page=&size=`: list (auth required).
- `GET /api/v1/quizzes?topic=&difficulty=&page=`: list quizzes.
- `GET /api/v1/quizzes/{id}`: detail w/ ordered exercise IDs (NO correct_answer leak; FE only sees question + options).
- `POST /api/v1/quizzes/{id}/submit` body `{answers: [{exerciseId, userAnswer, timeTakenMs}]}`: server scores → returns `{score, total, percent, perQuestion: [{exerciseId, isCorrect, correctAnswer, explanation}]}`.
- `POST /api/v1/exercises/{id}/attempt`: standalone (non-quiz) attempt.
- `GET /api/v1/me/weak-topics`: top 3 weakest topics.
- Admin: `POST/PUT/DELETE /api/v1/admin/{exercises,quizzes,quiz-exercises}`.

**Non-functional**
- Submission scoring transactional; all attempts persisted even on partial failure.
- `correct_answer` and `explanation` excluded from client DTOs except in submit response.

## Architecture
```mermaid
flowchart LR
  FE[Quiz Runner] -->|GET /quizzes/:id| BE
  BE -->|exercise list w/o answers| FE
  FE -->|user goes through Q1..Qn| FE
  FE -->|POST /quizzes/:id/submit answers[]| BE
  BE -->|score each, persist Attempt rows| PG[(attempts)]
  BE -->|publish QuizSubmittedEvent per correct| EVT[ApplicationEvent]
  BE -->|response w/ explanations| FE
```

## Related Code Files
**Backend `com.engly.grammar`**
- `controller/ExerciseController.java`
- `controller/QuizController.java`
- `controller/AdminGrammarController.java`
- `service/ExerciseService.java`
- `service/QuizService.java`
- `service/ScoringService.java` (pure-ish: `score(Exercise, String userAnswer) -> boolean`)
- `service/WeakTopicService.java`
- `repository/ExerciseRepository.java`, `QuizRepository.java`, `QuizExerciseRepository.java`, `AttemptRepository.java`
- `dto/ExerciseDto.java` (no correct_answer), `ExerciseAdminDto.java` (with), `QuizDto.java`, `QuizDetailDto.java`, `QuizSubmitRequest.java`, `QuizSubmitResponse.java`, `AttemptRequest.java`, `AttemptResultDto.java`, `WeakTopicDto.java`
- `mapper/ExerciseMapper.java`, `QuizMapper.java`, `AttemptMapper.java`
- `event/QuizExerciseCorrectEvent.java` (consumed by gamification)
- `event/QuizCompletedEvent.java` (perfect-quiz achievement check)

**Frontend**
- `app/quiz/page.tsx` — quiz list filtered by topic/difficulty.
- `app/quiz/[id]/page.tsx` — runner.
- `app/quiz/[id]/result/page.tsx` — result w/ explanations.
- `app/admin/exercises/page.tsx`, `app/admin/quizzes/page.tsx`.
- `components/features/quiz/quiz-runner.tsx`, `quiz-question.tsx`, `quiz-result.tsx`.
- `components/features/grammar/multiple-choice-question.tsx`, `fill-in-blank-question.tsx`, `sentence-construction-question.tsx`.
- `components/features/admin/exercise-form.tsx`, `quiz-form.tsx`.
- `hooks/use-quiz.ts`, `hooks/use-quiz-submit.ts`.
- `lib/api/grammar-api.ts`.

## Implementation Steps
1. `ExerciseRepository`:
   - Pageable `findByTopicAndDifficulty(String, String, Pageable)` with optional filters (use `Specification` or method overloading).
   - `List<Exercise> findAllByIdIn(Collection<UUID>)`.
2. `QuizRepository`: pageable lists; `findByIdWithExercises(UUID)` JOIN FETCH `quizExercises ORDER BY ordering`.
3. `AttemptRepository`:
   - `List<Attempt> findByUserIdAndCreatedAtAfter(UUID, Instant)`.
   - Aggregate query for weak-topics: native SQL or JPQL grouping by `e.topic`, computing `count(*)` and `sum(case when is_correct then 1 else 0 end)` joined to exercises.
4. `ScoringService.score(Exercise ex, String userAnswer)`:
```java
public boolean score(Exercise ex, String userAnswer) {
  String expected = normalize(ex.getCorrectAnswer(), ex.getType());
  String actual = normalize(userAnswer, ex.getType());
  return switch (ex.getType()) {
    case MULTIPLE_CHOICE -> expected.equals(actual);
    case FILL_IN_BLANK, SENTENCE_CONSTRUCTION -> expected.equals(actual);
  };
}
private String normalize(String s, ExerciseType type) {
  if (s == null) return "";
  String n = s.trim().toLowerCase().replaceAll("\\s+", " ");
  if (type == ExerciseType.SENTENCE_CONSTRUCTION) {
    n = n.replaceAll("[.!?]+$", "");
  }
  return n;
}
```
5. `QuizService.submitQuiz(UUID userId, UUID quizId, QuizSubmitRequest req)`:
   - Load quiz w/ exercises (single query w/ fetch join).
   - Build map `exerciseId -> Exercise`.
   - Iterate request answers; for each: score → build Attempt row → save in batch.
   - Compute score = correctCount, total = quiz.exercises.size().
   - Build response DTO with per-question feedback (include explanation + correct_answer for review).
   - Publish event per correct: `QuizExerciseCorrectEvent(userId, exerciseId, attemptId)`.
   - If `score == total`: publish `QuizCompletedEvent(userId, quizId, percent=1.0)`.
6. `ExerciseService` standalone attempt: same scoring path, single Attempt row.
7. `WeakTopicService.getWeakTopics(UUID userId, int n)`:
```java
@Query("""
  SELECT new com.engly.grammar.dto.WeakTopicDto(
    e.topic,
    COUNT(a),
    SUM(CASE WHEN a.isCorrect THEN 1 ELSE 0 END)
  )
  FROM Attempt a JOIN a.exercise e
  WHERE a.userId = :uid AND a.createdAt >= :since
  GROUP BY e.topic
  HAVING COUNT(a) >= 5
  ORDER BY (SUM(CASE WHEN a.isCorrect THEN 1.0 ELSE 0 END) / COUNT(a)) ASC
""")
List<WeakTopicDto> findWeakTopics(UUID uid, Instant since, Pageable pageable);
```
8. **Controllers**:
   - `ExerciseController`: GET list (DTO without `correct_answer` and `explanation`).
   - `QuizController`: GET list, GET detail (no answers leaked), POST submit, GET `/me/weak-topics`.
   - `AdminGrammarController`: full CRUD with admin DTOs (include answers).
9. **DTO discipline**: `ExerciseDto` (public) vs `ExerciseAdminDto` (admin) — separate MapStruct mappers, never expose `correct_answer` to non-admin endpoints.
10. **Bean Validation**:
    - `ExerciseAdminDto`: `@NotNull` type, `@NotBlank` question/correctAnswer, `@Pattern` cefr, `@Pattern` topic from controlled list.
    - `QuizSubmitRequest`: `@NotEmpty` answers, each `@Valid`.
11. **FE quiz runner**:
    - Local state: `currentIndex`, `answers: Record<exerciseId, string>`.
    - Load quiz detail; render one question at a time; Next/Prev buttons.
    - On final Submit: POST → navigate to result page with response in TanStack Query cache.
12. **FE per-type question components**:
    - MC: shadcn `RadioGroup`.
    - Fill-in-blank: `Input` (placeholder shows blank position).
    - Sentence-construction: `Input` long; show hint "punctuation/case ignored".
13. **FE result page**: list each question with badge (Correct / Incorrect), user's answer, correct answer (if wrong), explanation.
14. **FE admin pages**: shadcn `DataTable` + dialog forms with Zod schemas matching BE DTO. JSON editor (textarea) for `options`.
15. Smoke test: seed quizzes (V8 includes 3 quizzes built from seed exercises). Take quiz end-to-end → verify Attempt rows + weak-topic shifts.

## Todo List
- [ ] Repositories (Exercise, Quiz, QuizExercise, Attempt)
- [ ] ScoringService w/ normalization rules
- [ ] QuizService.submitQuiz transactional + events
- [ ] ExerciseService standalone attempt
- [ ] WeakTopicService aggregate query
- [ ] Controllers w/ DTO separation (public vs admin)
- [ ] Bean Validation
- [ ] MapStruct mappers (public vs admin)
- [ ] Event publishers (correct, completed)
- [ ] FE quiz list + detail + runner + result
- [ ] FE per-type question components
- [ ] FE admin exercises + quizzes pages
- [ ] V8 seed: 30 exercises + 3 quizzes (defined in phase-02 file but referenced here)

## Success Criteria
- Submit quiz w/ 10 questions → 10 Attempt rows + per-question feedback.
- Public quiz GET never includes `correct_answer`.
- Admin can create exercise → reflect in `/exercises` list.
- Weak-topics returns sorted ascending by accuracy with ≥5 attempts.
- Submit response received <500ms p95 for 10-question quiz.

## Risk Assessment
- **Risk:** Answer normalization differs between FE preview and BE judgment → **Mitigation:** BE is authoritative; FE shows "punctuation/case ignored" hint; document normalization in code-standards.
- **Risk:** Quiz submit double-charged XP if user re-submits → **Mitigation:** idempotency-key header (Phase 6 concern); MVP allows re-submission and counts each (acceptable).
- **Risk:** Large `options` JSONB → **Mitigation:** Bean Validation max-length on JSON string.
- **Risk:** N+1 on quiz-detail w/ exercises → **Mitigation:** JOIN FETCH in repo query.

## Security Considerations
- Public DTO never leaks `correct_answer` (test added in phase-08).
- Admin endpoints role-gated.
- User can only view own attempts (where applicable — MVP doesn't expose attempt-history list, defer to phase-07).
- Input length caps on `userAnswer` (1024 chars) to prevent payload abuse.

## Next Steps
[phase-06-gamification.md](./phase-06-gamification.md) — consumes `VocabReviewedEvent`, `QuizExerciseCorrectEvent`, `QuizCompletedEvent`.
