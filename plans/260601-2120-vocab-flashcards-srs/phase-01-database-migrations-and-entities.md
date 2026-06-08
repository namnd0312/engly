# Phase 01 — Database Migrations + JPA Entities

## Context Links
- Spec: `../reports/brainstorm-260601-2120-vocab-flashcards-srs.md` (§4 Data Model)
- Mirror: `engly-be/src/main/java/com/engly/verb/domain/IrregularVerb.java`, `engly-be/src/main/resources/db/migration/V1__create_irregular_verb_table.sql`
- Repo port pattern: `engly-be/src/main/java/com/engly/verb/domain/VerbRepository.java` + `infrastructure/VerbJpaRepository.java`

## Overview
- **Date:** 2026-06-01
- **Priority:** P1 (foundation — all other phases depend on it)
- **Current status:** pending
- **Brief:** Create Flyway V3 (4 vocab/study tables) + V4 seed (2 decks × 20 words). Add JPA entities + repository ports mirroring `verb` module layering. No API yet.

## Key Insights
- `IrregularVerb` uses `created_at` with `insertable=false, updatable=false` so the DB `DEFAULT now()` owns it — replicate for every `created_at`.
- `id GENERATED ALWAYS AS IDENTITY` in SQL maps to `GenerationType.IDENTITY`.
- `user_sub` is a plain string (OIDC `sub`), NOT a FK. No user table.
- `user_study_settings` PK is `user_sub` itself (no surrogate id).
- Entities are written here but the only consumer in this phase is `EnglyApplicationTests` (context loads → Hibernate validates mapping against migrated schema).

## Requirements
### Functional
- Schema for: decks, words, per-user card state, per-user settings.
- Seed 2 starter decks (Travel, Business), 20 words each, `verified=false`.
- `UNIQUE(user_sub, vocab_word_id)` on `user_card_state` (one state row per user/word).
- `UNIQUE` slug on `vocab_deck`.

### Non-functional
- Files: SQL not modularized; Java entities <200 LOC each, kebab-irrelevant (Java PascalCase classes) — descriptive class names.
- No data loss on existing verb tables; additive migrations only.

## Architecture
### New package layout (mirrors `verb`)
```
com.engly.vocab.domain        VocabDeck, VocabWord, *NotFoundException, *Repository (ports)
com.engly.vocab.infrastructure  VocabDeckJpaRepository, VocabWordJpaRepository
com.engly.study.domain        UserCardState, UserStudySettings, CardLearningState (enum), *Repository
com.engly.study.infrastructure  UserCardStateJpaRepository, UserStudySettingsJpaRepository
```
(Repositories/ports referenced here; query methods added in P2/P3 as needed — keep this phase to entities + create/findById basics.)

### Data flow
Flyway runs V3 then V4 on boot → Hibernate `validate` confirms entity↔table match.

### Schema (V3)
```
vocab_deck(id PK identity, name varchar(120) NOT NULL, slug varchar(140) NOT NULL UNIQUE,
           description varchar(500) NULL, topic varchar(80) NULL, created_at timestamptz DEFAULT now())
vocab_word(id PK identity, deck_id bigint NOT NULL REFERENCES vocab_deck(id) ON DELETE CASCADE,
           term varchar(120) NOT NULL, meaning_vi varchar(255) NOT NULL,
           ipa varchar(120) NULL, example_en varchar(500) NULL, example_vi varchar(500) NULL,
           part_of_speech varchar(40) NULL, verified boolean NOT NULL DEFAULT false,
           created_at timestamptz DEFAULT now())
user_card_state(id PK identity, user_sub varchar(100) NOT NULL,
           vocab_word_id bigint NOT NULL REFERENCES vocab_word(id) ON DELETE CASCADE,
           state varchar(16) NOT NULL,  -- NEW | LEARNING | REVIEW
           ease_factor double precision NOT NULL DEFAULT 2.5,
           interval_days int NOT NULL DEFAULT 0,
           repetitions int NOT NULL DEFAULT 0,
           lapses int NOT NULL DEFAULT 0,
           due_on date NULL, last_reviewed_at timestamptz NULL,
           created_at timestamptz DEFAULT now(),
           CONSTRAINT uq_user_card UNIQUE (user_sub, vocab_word_id))
user_study_settings(user_sub varchar(100) PRIMARY KEY,
           new_cards_per_day int NOT NULL DEFAULT 20,
           max_reviews_per_day int NULL,  -- null = unlimited
           updated_at timestamptz DEFAULT now())
```
### Indexes
- `idx_vocab_word_deck` ON `vocab_word(deck_id)`
- `idx_user_card_due` ON `user_card_state(user_sub, due_on)`

## Related Code Files
### Create
- `engly-be/src/main/resources/db/migration/V3__create_vocab_and_study_tables.sql`
- `engly-be/src/main/resources/db/migration/V4__seed_starter_decks_travel_business.sql`
- `engly-be/src/main/java/com/engly/vocab/domain/VocabDeck.java`
- `engly-be/src/main/java/com/engly/vocab/domain/VocabWord.java`
- `engly-be/src/main/java/com/engly/vocab/domain/VocabDeckNotFoundException.java`
- `engly-be/src/main/java/com/engly/vocab/domain/VocabWordNotFoundException.java`
- `engly-be/src/main/java/com/engly/vocab/infrastructure/VocabDeckJpaRepository.java`
- `engly-be/src/main/java/com/engly/vocab/infrastructure/VocabWordJpaRepository.java`
- `engly-be/src/main/java/com/engly/study/domain/CardLearningState.java` (enum NEW/LEARNING/REVIEW)
- `engly-be/src/main/java/com/engly/study/domain/UserCardState.java`
- `engly-be/src/main/java/com/engly/study/domain/UserStudySettings.java`
- `engly-be/src/main/java/com/engly/study/infrastructure/UserCardStateJpaRepository.java`
- `engly-be/src/main/java/com/engly/study/infrastructure/UserStudySettingsJpaRepository.java`

### Modify
- None (additive). Confirm `spring.jpa.hibernate.ddl-auto=validate` (or none) in `application.yml`.

### Delete
- None.

## Implementation Steps
1. Write `V3__create_vocab_and_study_tables.sql` with the 4 tables + 2 indexes above. Use `CREATE TABLE IF NOT EXISTS`, `timestamptz DEFAULT now()`, `ON DELETE CASCADE` FKs.
2. Write `V4__seed_starter_decks_travel_business.sql`: INSERT 2 decks (slugs `travel`, `business`), then 20 `vocab_word` rows each (`verified=false`). Use sub-selects on slug for `deck_id` (avoid hardcoding ids). Author plausible EN→VI pairs + part_of_speech; IPA/examples may be NULL.
3. `CardLearningState` enum: `NEW, LEARNING, REVIEW`.
4. `VocabDeck` entity: fields id/name/slug/description/topic/createdAt; `update(name, description, topic)` full-state mutator (slug immutable after create — derived). Getters only.
5. `VocabWord` entity: `@ManyToOne(fetch=LAZY)` to `VocabDeck` via `deck_id` (or store `deckId` Long directly for KISS — prefer `deckId` Long to avoid lazy-loading complexity, mirroring flat verb entity). Decision: **store `deckId` as Long column**, fetch deck separately when needed. Fields + `update(...)` mutator + `setVerified`.
6. `UserCardState` entity: id, userSub, vocabWordId (Long), state (`@Enumerated(STRING)`), easeFactor (double), intervalDays, repetitions, lapses, dueOn (LocalDate), lastReviewedAt (Instant), createdAt. Add a single `applyScheduling(state, ef, interval, reps, lapses, dueOn, lastReviewedAt)` mutator (used by P3). Static factory `newCard(userSub, wordId)` defaulting EF=2.5, state=NEW, reps=0, interval=0, lapses=0, dueOn=null.
7. `UserStudySettings` entity: `@Id userSub`, newCardsPerDay, maxReviewsPerDay (Integer nullable), updatedAt. Mutator `update(newCardsPerDay, maxReviewsPerDay)`. Static factory `defaults(userSub)`.
8. Repository ports + JPA interfaces: `VocabDeckJpaRepository extends JpaRepository<VocabDeck,Long>` (+ `findBySlug`), `VocabWordJpaRepository` (+ `findByDeckId(Long, Pageable)`), `UserCardStateJpaRepository` (+ `findByUserSubAndVocabWordId`), `UserStudySettingsJpaRepository` (PK String). Keep derived queries; advanced queue queries added in P3.
9. Run BE build/migration check.

## Todo List
- [ ] V3 migration (4 tables + 2 indexes)
- [ ] V4 seed (2 decks × 20 words, verified=false)
- [ ] CardLearningState enum
- [ ] VocabDeck + VocabWord entities + mutators
- [ ] UserCardState + UserStudySettings entities + factories/mutators
- [ ] NotFound exceptions (vocab deck + word)
- [ ] 4 JPA repository interfaces with basic derived queries
- [ ] Confirm ddl-auto=validate; boot app to run Flyway
- [ ] Compile/build check

## Success Criteria
- `./mvnw -q compile` passes.
- App boots against Postgres: Flyway applies V3 + V4 with no error; Hibernate `validate` passes (entities match schema).
- `psql`: `SELECT count(*) FROM vocab_word` returns 40; `SELECT count(*) FROM vocab_deck` returns 2.

## Risk Assessment
- **Hibernate validation mismatch** (column type/length): mitigate by matching SQL types to entity exactly (double precision ↔ double, varchar lengths).
- **Seed FK resolution**: use `(SELECT id FROM vocab_deck WHERE slug='travel')` sub-selects, not literal ids.
- **`due_on` nullable**: NEW cards have no due date until first review — column NULL allowed.

## Security Considerations
- No PII beyond `user_sub` (opaque OIDC id). No auth surface added this phase.
- FK `ON DELETE CASCADE` ensures deleting a word/deck cleans dependent `user_card_state` (no orphan progress).

## Next Steps
- Unblocks **P2** (vocab admin CRUD) and **P3** (study API) in parallel.

## Compile/Build Check
```
cd engly-be && ./mvnw -q -DskipTests compile
# then boot once (or run EnglyApplicationTests) to apply Flyway + validate mapping
./mvnw -q -Dtest=EnglyApplicationTests test
```
