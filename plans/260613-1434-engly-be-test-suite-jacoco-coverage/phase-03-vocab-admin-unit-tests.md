# Phase 03 — Vocab Admin Unit Tests

## Context Links
- Scout cases: `scout/scout-01-coverage-gap-map.md` §§8,9 (DeckAdminService, VocabWordAdminService)
- Pattern to copy: `engly-be/src/test/java/com/engly/verb/VerbQueryServiceTest.java`
- Existing integration coverage (do NOT duplicate happy CRUD): `engly-be/src/test/java/com/engly/vocab/VocabAdminIntegrationTest.java`

## Overview
- **Date:** 2026-06-13
- **Description:** Mockito unit tests for the two vocab admin services, focused on the branch logic the integration test does NOT exercise: LIKE-escaping, paging validation bounds, the 4-branch word search router, deck resolution failure, and verified-toggle. Fast, no DB.
- **Priority:** P2
- **Implementation status:** complete
- **Review status:** pending

## Key Insights
- Both services are in `com.engly.vocab.admin`; no package-private DTOs → tests can sit in `com.engly.vocab.admin` (mirror main package) for clean access.
- `escapeLike` order matters: `!`→`!!` FIRST, then `%`→`!%`, then `_`→`!_` (so the escape char itself isn't double-escaped). Tests must assert exact escaped string passed to repo.
- Word search has 4 branches gated by `(deckId != null, hasQ)` — verify each routes to the correct repo method (`searchByDeck` / `findByDeck_Id` / `search` / `findAll`) with the escaped/ trimmed term.
- Paging validation (`page>=0`, `1<=size<=100`) throws `IllegalArgumentException` BEFORE any repo call — assert no repo interaction on invalid input.
- `VocabWordAdminService.apply()` calls `resolveDeck()` → missing deck throws `VocabDeckNotFoundException` on both create and update.

## Requirements
**Functional** — per-class cases below; genuine assertions on repo args via `ArgumentCaptor`/`eq()`.
**Non-functional** — each file < 200 LOC; pure Mockito.

## Architecture
`@ExtendWith(MockitoExtension.class)` + `@Mock` repos + constructor-injected service. Use `PageImpl` for repo returns (as in `VerbQueryServiceTest`). Build `VocabDeck`/`VocabWord` via constructors + reflection helper where setters absent.

## Related Code Files
**Create** (under `src/test/java/com/engly/vocab/admin/`):
| Test file | Target class |
|-----------|--------------|
| `DeckAdminServiceTest.java` | `com.engly.vocab.admin.DeckAdminService` |
| `VocabWordAdminServiceTest.java` | `com.engly.vocab.admin.VocabWordAdminService` |

**Modify / Delete** — none.

## Implementation Steps
1. **DeckAdminServiceTest** — mock `VocabDeckJpaRepository`. Cases:
   - `search(null,...)` → `findAll(pageable)`, never `search`.
   - `search("   ",...)` (blank) → `findAll`.
   - `search("go",...)` → `repository.search(eq("go"), pageable)` (trimmed, no wildcard).
   - LIKE escaping: `search("a%_!",0,20)` → captured arg == `a!%!_!!`? (verify exact, mind order: `!`→`!!` first → `a%_!!`... build expected by applying same order: input `a%_!` → `!`→`!!` ⇒ `a%_!!` → `%`→`!%` ⇒ `a!%_!!` → `_`→`!_` ⇒ `a!%!_!!`). Assert captured string equals that.
   - paging: `page=-1`→IAE "page must be >= 0", no repo call; `size=101`→IAE "size must be between"; `size=0`→IAE; `size=100`→passes (stub `findAll`/`search`).
   - `getById(id)` not found → `VocabDeckNotFoundException`.
   - `create(req)` → `save` called; returned DTO maps id/name/slug/description/topic.
   - `update(id,req)` not found → exception; found → `deck.update(name,desc,topic)` (slug unchanged — verify slug not passed).
   - `delete(id)` not found (`existsById`→false) → `VocabDeckNotFoundException`; found → `deleteById`.
2. **VocabWordAdminServiceTest** — mock `VocabWordJpaRepository` + `VocabDeckJpaRepository`. Cases:
   - search 4 branches:
     - `(deckId=1, q="apple")` → `searchByDeck(eq(1L), eq("apple"), pageable)`.
     - `(deckId=1, q=null)` → `findByDeck_Id(1L, pageable)`.
     - `(deckId=null, q="apple")` → `search(eq("apple"), pageable)`.
     - `(deckId=null, q=null)` → `findAll(pageable)`.
     - `(deckId=1, q="   ")` blank → `findByDeck_Id` (hasQ false).
   - LIKE escaping on `searchByDeck` and `search` paths (same expected-string method as deck).
   - paging validation bounds (same as deck): -1, 0, 101 → IAE; 100 → pass.
   - create: deck resolves → `save`; deck missing (`deckRepository.findById`→empty) → `VocabDeckNotFoundException`.
   - update: found word + deck resolves → reassign + `save`; deck missing → exception; word missing → `VocabWordNotFoundException`.
   - `toggleVerified(id,true)` found → `setVerified(true)` + save (other fields untouched — assert via captor); not found → `VocabWordNotFoundException`.
   - `delete`/`getById` not found → `VocabWordNotFoundException`.
3. Compile + run: `cd engly-be && ./mvnw test -Dtest='DeckAdminServiceTest,VocabWordAdminServiceTest'`.

## Todo List
- [x] `DeckAdminServiceTest` (search routing, escaping, paging, CRUD/404)
- [x] `VocabWordAdminServiceTest` (4 search branches, escaping, paging, deck-resolution 404, verified toggle)
- [x] Verify escaped-string args via `ArgumentCaptor`
- [x] All compile + pass; each file < 200 LOC

## Success Criteria
- Both files pass via `./mvnw test`.
- `vocab.admin` services LINE coverage > 90% in JaCoCo HTML (branch router fully exercised).
- No duplication of integration-test happy CRUD assertions (DRY).

## Risk Assessment
- **Escape-order mismatch** in expected strings → false failures. Mitigation: compute expected by literally applying the 3 replacements in service order; document in test comment.
- **Strict stubbing** on branch tests. Mitigation: per-test stubs; `lenient()` only for shared setup.
- **Entity field access** without setters. Mitigation: reuse `VerbQueryServiceTest` reflection helper.

## Security Considerations
- None at unit layer; `/api/admin/**` RBAC verified in phase-04/05.

## Next Steps
- Independent of phases 02/04. Feeds phase-06. Admin controller edge/auth cases handled in phase-05.
