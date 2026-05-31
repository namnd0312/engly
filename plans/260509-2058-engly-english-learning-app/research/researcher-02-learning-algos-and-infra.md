# Research: Learning Algorithms, Redis Patterns & Spring Boot Infrastructure

## Topic A: SM-2 Spaced Repetition Algorithm

**Recommendation:** Implement SM-2 as MVP core; FSRS as future upgrade path.

**Why:** SM-2 is battle-tested (Anki, SuperMemo since 1987), simple, deterministic. Requires only 4 fields per card. FSRS (Free Spaced Repetition Scheduler, available in Anki 23.10+) uses ML but adds complexity—defer to Phase 2.

**SM-2 Formula Sketch:**
- Quality scores: 0–5 (Anki mapping: Again=0, Hard=2, Good=3, Easy=4)
- `newEase = max(1.3, ease + 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))`
- If quality < 3 (lapses): reset interval=0, demote to LEARNING state
- If quality ≥ 3: `newInterval = interval == 0 ? 1 : (interval == 1 ? 6 : round(interval * ease))`

**Card Entity Fields:**
```
ease_factor: double = 2.5         // Ease multiplier
interval: int = 0                 // Days until next review
repetitions: int = 0              // Successful reviews in row
next_review_date: LocalDateTime   // When to show card
state: enum = NEW                 // NEW, LEARNING, REVIEW, RELEARNING
lapses: int = 0                   // Total fails (for stats)
```

**Learning Steps Workflow:**
- NEW → LEARNING (e.g., show immediately then after 10min, 1day)
- After final learning step + quality ≥ 3 → REVIEW (use SM-2 interval)
- REVIEW + quality < 3 → RELEARNING (reset interval, 1-day steps, then back to REVIEW)

**Java Implementation (pure function):**
```java
public static Card scheduleNext(Card card, int quality) {
    if (quality < 3) {
        card.lapses++;
        card.repetitions = 0;
        card.state = CardState.RELEARNING;
        card.interval = 0;
        card.nextReviewDate = now().plus(1, ChronoUnit.DAYS);
    } else {
        card.repetitions++;
        card.state = CardState.REVIEW;
        int newInterval = card.interval == 0 ? 1 : 
                          card.interval == 1 ? 6 : 
                          Math.round(card.interval * card.easeFactor);
        card.interval = newInterval;
        card.nextReviewDate = now().plus(newInterval, ChronoUnit.DAYS);
    }
    card.easeFactor = Math.max(1.3, card.easeFactor + 0.1 - 
                       (5 - quality) * (0.08 + (5 - quality) * 0.02));
    return card;
}
```

**Edge Cases:**
- Minimum ease floor: 1.3 (prevent interval collapse)
- First two intervals fixed: 1 day, 6 days (learning→review transition)
- Timezone handling on `nextReviewDate`: store as UTC in DB, compute due-time per user TZ on read

**Pitfall:** Beware interval overflow for very high ease + many reps. Cap max interval (e.g., 36500 days = 100 years).

**References:** [Anki spaced repetition algorithm](https://faqs.ankiweb.net/what-spaced-repetition-algorithm) | [SuperMemo2 pseudo-code](https://memoet.gitbook.io/docs/reference-guides/supermemo2)

---

## Topic B: Redis Sorted Sets for Leaderboards

**Recommendation:** Use ZSetOperations (Spring Data Redis); dedicated Redis DB index for leaderboard keys; atomic ZINCRBY only (no read-modify-write).

**Why:** ZINCRBY is O(log N) atomic, eliminates race conditions. Millions of updates/sec capable. Write-amplification low if using connection pooling.

**Key Design:**
- **Alltime:** `leaderboard:xp:alltime`
- **Weekly:** `leaderboard:xp:weekly:2026-W19` (ISO week format)
- **Member ID:** User ID integer (not username—handles renames gracefully)
- **Score:** XP accumulated (int or double, microseconds precision if needed)

**Spring Data Redis Operations:**
```java
ZSetOperations<String, String> zops = redisTemplate.opsForZSet();

// Atomic XP award
zops.incrementScore("leaderboard:xp:alltime", userId.toString(), xpGained);

// Top 10 (with scores)
Set<ZSetOperations.TypedTuple<String>> top10 = 
    zops.reverseRangeWithScores("leaderboard:xp:alltime", 0, 9);

// User rank + 3 neighbors above/below
Long rank = zops.reverseRank("leaderboard:xp:alltime", userId.toString());
if (rank != null) {
    Set<ZSetOperations.TypedTuple<String>> neighbors = 
        zops.reverseRangeWithScores("leaderboard:xp:alltime", 
            Math.max(0, rank - 3), rank + 3);
}
```

**Weekly Leaderboard TTL Strategy:**
- On first XP award of week: `EXPIRE leaderboard:xp:weekly:2026-W19 8d` (7 + 1 buffer)
- Redis auto-purges at expiry; no cron needed
- Compute week key server-side: `LocalDate.now(ZoneId.of("UTC")).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)` + year

**Concurrency:**
- ZINCRBY is atomic (single Redis command, no locks needed)
- No read-modify-write anti-pattern
- Connection pooling (Lettuce via Spring Data Redis) handles multiple concurrent increments

**Pitfalls:**
- Hot-key problem if single leaderboard written >10k/sec: shard by region/level (e.g., `leaderboard:xp:asia:alltime`)
- Eviction policy: use `noeviction` on Redis instances storing leaderboards (or dedicated DB index 1, main cache DB 0)
- Member count explosion: quarterly archive old weeks to separate Redis or cold storage

**References:** [Redis leaderboard tutorial](https://redis.io/tutorials/howtos/leaderboard/) | [Medium: Spring Boot Redis sorted sets](https://medium.com/@asgreen200/leaderboard-using-sorted-set-redis-spring-boot-e02b4cd269e4)

---

## Topic C: Streak Tracking with Timezone Correctness

**Recommendation:** Store user timezone (IANA string), compute "today" per user, use lazy-on-read reset logic, optimistic locking on user_progress row.

**Why:** KISS—no cron jobs. ZoneId + LocalDate handle DST correctly. Optimistic locking avoids deadlocks under concurrent activity.

**Schema:**
```sql
user_progress (
    user_id: UUID,
    timezone: varchar(50) = "UTC",           -- e.g., "Asia/Saigon"
    last_activity_date: date,                -- Date in user's TZ (not UTC)
    current_streak: int = 0,
    longest_streak: int = 0,
    version: int = 0                        -- Optimistic lock version
)
```

**Streak Update Logic (on activity):**
```java
UserProgress update(UUID userId, String userTz) {
    LocalDate today = LocalDate.now(ZoneId.of(userTz));
    UserProgress prog = repo.findByUserId(userId);
    
    if (prog.lastActivityDate == null) {
        prog.currentStreak = 1;
    } else if (prog.lastActivityDate.equals(today)) {
        return prog;  // Already updated today—idempotent
    } else if (prog.lastActivityDate.equals(today.minusDays(1))) {
        prog.currentStreak++;  // Consecutive day
    } else {
        prog.currentStreak = 1;  // Streak reset
    }
    
    prog.lastActivityDate = today;
    prog.longestStreak = Math.max(prog.longestStreak, prog.currentStreak);
    return repo.save(prog);  // JPA optimistic lock on version field
}
```

**DST Handling:**
- Java 8+ `ZoneId` is TZDB-aware; LocalDate boundaries are correct across DST transitions
- Example: "Asia/Saigon" has no DST; "America/New_York" transitions correctly
- Keep JRE updated (annual TZdata releases)

**Race Conditions (idempotency):**
- **Optimistic locking:** JPA `@Version` on `user_progress.version`; if concurrent writes, 2nd thread retries with fresh data
- **Redis idempotency key:** Optional—use `SET nx` on `activity:userId:YYYY-MM-DD` if high-concurrency (batch updates) to avoid double-counting within same calendar day

**Pitfall:** Never use `new Date()` or `System.currentTimeMillis()` for "today"—always `LocalDate.now(ZoneId.of(userTz))`.

**Lazy vs Batch:**
- Lazy on-read (current design): 0 infrastructure, compute once per user activity
- Batch reset job: needs cron, complexity, but fine if >1M daily users later

**References:** [Baeldung: DST in Java](https://www.baeldung.com/java-daylight-savings) | [Java timezone best practices](https://www.oracle.com/java/technologies/javase/timezones.html)

---

## Topic D: Spring Boot 3.4 Project Layout (Single-Module, Package-by-Feature)

**Recommendation:** Single module (Maven/Gradle root project) with package-by-feature structure for MVP. No multi-module until team >3 backend devs or services split.

**Why:** YAGNI. Single module reduces Gradle complexity, faster build, simpler onboarding. Package-by-feature groups related classes (entity, service, controller, dto, mapper, repo) → easier to reason about feature scope.

**Build File (build.gradle.kts):**
```kotlin
plugins {
    id("java")
    id("org.springframework.boot") version "3.4.0"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.engly"
version = "0.1.0"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
}

dependencies {
    // Core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    
    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    
    // Mapping
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    
    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers:1.20.0")
    testImplementation("org.testcontainers:postgresql:1.20.0")
    testImplementation("org.testcontainers:toxiproxy:1.20.0")
}
```

**Package Structure:**
```
src/main/java/com/engly/
├── auth/
│   ├── AuthController.java
│   ├── AuthService.java
│   ├── AuthRepository.java
│   ├── User.java
│   ├── UserDto.java
│   └── UserMapper.java
├── vocabulary/
│   ├── VocabularyController.java
│   ├── VocabularyService.java
│   ├── VocabularyRepository.java
│   ├── Card.java
│   ├── CardDto.java
│   └── CardMapper.java
├── gamification/
│   ├── LeaderboardService.java
│   ├── UserProgress.java
│   └── StreakService.java
├── common/
│   ├── exception/
│   │   └── ApiException.java
│   ├── config/
│   │   ├── RedisConfig.java
│   │   └── JpaConfig.java
│   └── util/
│       └── TimeUtils.java
└── EnnglyApplication.java

src/main/resources/
├── application.yml
├── application-dev.yml
├── application-test.yml
└── db/migration/
    ├── V1__initial_schema.sql
    └── V2__...sql
```

**Spring Profiles:**
```yaml
# application.yml (defaults)
spring:
  application.name: engly
  profiles.active: dev
  
# application-dev.yml
spring:
  datasource.url: jdbc:postgresql://localhost:5432/engly_dev
  redis.host: localhost
  jpa.hibernate.ddl-auto: create-drop

# application-test.yml (Testcontainers override)
spring:
  datasource.driver-class-name: org.postgresql.Driver
  redis.host: localhost  # Override via @ServiceConnection

# application-prod.yml
spring:
  jpa.hibernate.ddl-auto: validate
  redis.ssl: true
```

**When to Multi-Module:**
- Team grows; BE split into service A (auth), service B (vocab), service C (gamification)
- CI parallelization benefit justifies Gradle overhead (>5 min unified build time)
- Clear module boundaries; minimal cross-module coupling

**References:** [Gradle Kotlin DSL docs](https://docs.gradle.org/current/userguide/kotlin_dsl.html) | [Spring Boot Kotlin DSL guide](https://oneuptime.com/blog/post/2026-02-02-kotlin-dsl-spring-boot/view)

---

## Topic E: Testcontainers for Postgres + Redis in Spring Boot 3

**Recommendation:** Use `@ServiceConnection` + `@Testcontainers` + static `@Container` fields + container reuse enabled; `@SpringBootTest` for full integration tests, `@DataJpaTest` for repo-only tests.

**Why:** `@ServiceConnection` (Spring Boot 3.1+) auto-wires JdbcConnectionDetails & RedisConnectionDetails—no property stringly-typing. Reuse containers across test runs (saves startup time ~10s per test class).

**Setup (AbstractIntegrationTest):**
```java
@Testcontainers
@SpringBootTest
class AbstractIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = 
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
        .withDatabaseName("engly_test");
    
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = 
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);
}

// Usage: extend AbstractIntegrationTest in all integration test classes
class UserServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private UserService userService;
    
    @Test
    void testCreateUser() { ... }
}
```

**Container Reuse (testcontainers.properties):**
```
# ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

Containers survive test suite run; stops on JVM exit. Saves ~10–30s per test run during development.

**Test Slicing:**
- `@DataJpaTest`: Only JPA layer (no Service/Controller); sets up DataSource + JpaConfig only. Faster for repo tests.
  ```java
  @DataJpaTest
  class UserRepositoryTest {
      @Autowired
      private UserRepository repo;
      // No need for full Spring context
  }
  ```
- `@SpringBootTest`: Full context (all beans, properties, controllers). Use for end-to-end scenarios.

**Parallel Tests (PER-CLASS ISOLATION):**
- Risk: Flyway migrations run in parallel; concurrent schema init → deadlock
- Solution: Use `@DirtiesContext(classMode = AFTER_CLASS)` OR separate schema per test class
- Simplest: Run tests sequentially (default). If parallelism needed, coordinate with team.

**Gotchas:**
- Redis persistence: `GenericContainer` doesn't persist by default (OK for tests)
- Postgres cleanup: Flyway `clean` on startup; use `spring.flyway.cleanDisabled=true` in prod
- SSL/Auth: Add `.withEnv("POSTGRES_PASSWORD", "test")` if needed
- Timeout: Default 60s startup; increase with `.withStartupTimeout(Duration.ofMinutes(2))`

**@DynamicPropertySource (Alternative for Manual Control):**
```java
@Testcontainers
@SpringBootTest
class CustomPropertyTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer<>("postgres:16");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```
Use when `@ServiceConnection` doesn't auto-detect (e.g., custom container image).

**References:** [Spring Boot Testcontainers docs](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html) | [Spring Blog: improved Testcontainers 3.1](https://spring.io/blog/2023/06/23/improved-testcontainers-support-in-spring-boot-3-1)

---

## Decisions for Planner

1. **SM-2 MVP, FSRS future:** Implement SM-2 spaced repetition now. FSRS as Phase-2 research task.
2. **Alltime + weekly leaderboards:** Use Redis sorted sets with `leaderboard:xp:alltime` + `leaderboard:xp:weekly:ISO-WEEK` keys, 8-day TTL on weekly.
3. **Single-module Go-live:** Keep single module package-by-feature for MVP. Revisit multi-module at 3+ backend devs or services split.
4. **Testcontainers + @ServiceConnection:** Use static containers + reuse in all integration tests; `@DataJpaTest` for repo tests, `@SpringBootTest` for full flows.
5. **Lazy streak reset:** No cron—compute "today" per user TZ on activity, optimistic locking on user_progress row.

## Unresolved Questions

- **FSRS feasibility timeline:** When/how should we pilot FSRS? Separate Redis model or replace SM-2 in-place?
- **Leaderboard sharding threshold:** At what XP write QPS do we need regional shard strategy (e.g., `leaderboard:xp:asia:alltime`)?
- **Team scale assumptions:** Multi-module plan assumes 3+ backend devs; confirm with product manager.
- **Timezone edge case:** Do users set timezone once (profile edit) or auto-detect + allow override? Impacts data validation.
- **Concurrent streak edge case:** Should rapid activity within same minute cause double-increment, or enforce per-calendar-day exactly-once (Redis idempotency key)?
