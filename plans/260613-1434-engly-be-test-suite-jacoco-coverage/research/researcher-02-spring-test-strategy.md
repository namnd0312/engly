# Spring Boot 3.4.5 / JUnit 5 Layered Testing Strategy for Engly BE

**Date:** 2026-06-13 | **Token-efficient research covering 5 core test patterns**

---

## 1. Decision Matrix: Test Type per Engly Layer

| Layer | Test Type | Tool | When | Example |
|-------|-----------|------|------|---------|
| **REST Controller** (`api/*`, `admin/*`) | `@WebMvcTest` slice | MockMvc + mocked @Service | Happy path + error responses | POST /api/vocab with auth |
| **Application Service** | Plain JUnit + Mockito | `@ExtendWith(MockitoExtension.class)` | Fast unit tests, verify interactions | `VocabService.importCsv()` logic |
| **JPA Repository** | `@DataJpaTest` | Testcontainers Postgres | Query correctness on real schema | `VocabWordRepository.findByLevelAndReviewed()` |
| **Domain Logic** (SM2 scheduler) | Parameterized JUnit 5 | `@ParameterizedTest + @CsvSource` | Pure functions, multiple inputs | Rating 4 → 10-day interval |
| **Full E2E** | `@SpringBootTest + @AutoConfigureMockMvc` | Real Spring context + Testcontainers | Entire request pipeline with auth | OAuth2 token → admin endpoint |

**Key:** Start bottom-up (unit → slice → integration). Minimize `@SpringBootTest` (expensive, slow).

---

## 2. Spring Boot 3.4 Specifics

### @MockitoBean / @MockitoSpyBean (Replaces Deprecated @MockBean)

**Before (deprecated):**
```java
@MockBean
VocabService vocabService;
```

**After (Spring Boot 3.4):**
```java
@WebMvcTest(VocabController.class)
class VocabControllerTest {
    @MockitoBean
    private VocabService vocabService;  // Auto-creates Mockito mock, injects into context
}
```

### @ServiceConnection + Testcontainers (Postgres Auto-Wiring)

**Modern approach (Spring Boot 3.1+, replaces manual `@DynamicPropertySource`):**

```java
@SpringBootTest
@Testcontainers
class VocabRepositoryIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = 
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("engly_test")
            .withUsername("test")
            .withPassword("test");
    
    // @ServiceConnection auto-registers datasource bean
    @ServiceConnection
    static PostgreSQLContainer<?> container = postgres;
    
    @Autowired
    private VocabWordRepository repo;
    
    @Test
    void shouldFetchWordsAtLevel() {
        // Uses real Postgres via datasource auto-configured from @ServiceConnection
    }
}
```

**Why:** `@ServiceConnection` discovers container type, creates `ConnectionDetails` bean, Spring auto-config consumes it. Zero manual properties needed.

---

## 3. Spring Security Testing: RBAC on /api/admin/**

### @WithMockUser (Simple RBAC)

```java
@WebMvcTest(AdminDeckController.class)
class AdminDeckControllerTest {
    
    @MockitoBean
    private DeckService deckService;
    
    @Autowired
    private MockMvc mvc;
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldCreateDeckWhenAdmin() throws Exception {
        mvc.perform(post("/api/admin/decks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Test"}"""))
            .andExpectAll(
                status().isCreated(),
                jsonPath("$.id").exists()
            );
    }
    
    @Test
    @WithAnonymousUser
    void shouldReturn401WhenNotAuthenticated() throws Exception {
        mvc.perform(post("/api/admin/decks"))
            .andExpect(status().isUnauthorized());  // 401
    }
    
    @Test
    @WithMockUser(roles = "USER")  // Not ADMIN
    void shouldReturn403WhenInsufficientRole() throws Exception {
        mvc.perform(post("/api/admin/decks"))
            .andExpect(status().isForbidden());  // 403
    }
}
```

### JWT Bearer Token Testing (OAuth2 Resource Server)

For Engly's OPEN-GATE OAuth2 setup with JWT in id_token:

```java
@WebMvcTest(AdminVocabController.class)
class AdminVocabControllerJwtTest {
    
    @MockitoBean
    private VocabService vocabService;
    
    @MockitoBean
    private JwtDecoder jwtDecoder;  // Mock to avoid issuer-uri calls
    
    @Autowired
    private MockMvc mvc;
    
    @Test
    void shouldAllowAdminRequestWithValidJwt() throws Exception {
        mvc.perform(get("/api/admin/vocab-words")
                .with(jwt()
                    .jwt(jwt -> jwt
                        .subject("user@example.com")
                        .claim("roles", List.of("ROLE_ADMIN")))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
    }
    
    @Test
    void shouldReject403WithoutAdminClaim() throws Exception {
        mvc.perform(get("/api/admin/vocab-words")
                .with(jwt()
                    .jwt(jwt -> jwt.claim("roles", List.of("ROLE_USER")))))
            .andExpect(status().isForbidden());
    }
}
```

**Key method:** `SecurityMockMvcRequestPostProcessors.jwt()` builds JwtAuthenticationToken without calling issuer-uri.

---

## 4. Pure Algorithm Testing: SM2 Spaced Repetition

**Parameterized test for rating → interval logic:**

```java
class SM2SchedulerTest {
    
    private SM2Scheduler scheduler;
    
    @BeforeEach
    void setup() {
        scheduler = new SM2Scheduler();
    }
    
    @ParameterizedTest(name = "Rating {0} → {1} days")
    @CsvSource({
        "1, 1",      // Difficult: 1 day
        "2, 3",      // Hard: 3 days
        "3, 7",      // Good: 7 days
        "4, 10",     // Easy: 10 days
        "5, 14"      // Very easy: 14 days
    })
    void shouldCalculateIntervalByRating(int rating, int expectedDays) {
        Interval result = scheduler.calculateNextReview(rating);
        assertThat(result.getDays()).isEqualTo(expectedDays);
    }
    
    @ParameterizedTest
    @MethodSource("provideComplexScenarios")
    void shouldHandleMultipleReviews(int initialRating, int secondRating, int expectedFinalDays) {
        Interval first = scheduler.calculateNextReview(initialRating);
        Interval second = scheduler.calculateNextReview(secondRating, first);
        
        assertThat(second.getDays()).isEqualTo(expectedFinalDays);
    }
    
    static Stream<Arguments> provideComplexScenarios() {
        return Stream.of(
            Arguments.of(3, 5, 21),  // Good → Very Easy: 7 * 1.3 ≈ 10 → 14
            Arguments.of(2, 4, 15)   // Hard → Easy: 3 → 7 → 10
        );
    }
}
```

**Why:** Zero Spring overhead. Pure logic tested against multiple inputs. DRY (avoids test duplication).

---

## 5. Service Unit Tests with Exception Handling

```java
@ExtendWith(MockitoExtension.class)
class VocabServiceTest {
    
    @Mock
    private VocabWordRepository wordRepo;
    
    @Mock
    private DeckRepository deckRepo;
    
    @InjectMocks
    private VocabService service;
    
    @Test
    void shouldThrowNotFoundWhenDeckMissing() {
        when(deckRepo.findById(99L)).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.getDeck(99L))
            .isInstanceOf(DeckNotFoundException.class)
            .hasMessageContaining("Deck 99 not found");
    }
    
    @Test
    void shouldVerifyRepositoryInteraction() {
        service.importVocab(List.of(new VocabWord("apple")));
        
        verify(wordRepo, times(1)).saveAll(argThat(
            words -> words.size() == 1 && words.get(0).getTerm().equals("apple")
        ));
    }
}
```

### GlobalExceptionHandler Unit Test (standaloneSetup)

```java
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {
    
    private MockMvc mvc;
    
    @BeforeEach
    void setup() {
        // Register handler without full context
        mvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }
    
    @Test
    void shouldReturn404BodyForNotFoundException() throws Exception {
        // TestController throws DeckNotFoundException on GET /test
        mvc.perform(get("/test"))
            .andExpectAll(
                status().isNotFound(),
                jsonPath("$.error").value("Deck not found"),
                jsonPath("$.timestamp").exists()
            );
    }
    
    @RestController
    static class TestController {
        @GetMapping("/test")
        void throwNotFound() {
            throw new DeckNotFoundException("Deck not found");
        }
    }
}
```

**Key:** `standaloneSetup()` avoids full Spring context. `setControllerAdvice()` registers the handler.

---

## 6. Async Import Processor Testing

**Problem:** Flaky async tests with `@Async` CSV importer.

**Solution 1: Make it deterministic (preferred)**

```java
@ExtendWith(MockitoExtension.class)
class VocabImportServiceTest {
    
    @Mock
    private VocabWordRepository wordRepo;
    
    @InjectMocks
    private VocabImportService service;
    
    @Test
    void shouldImportCsvSynchronously() throws Exception {
        // Call sync method directly, skip async
        service.importCsvSync(new FileInputStream("vocab.csv"));
        
        verify(wordRepo, times(3)).save(any());
    }
}
```

**Solution 2: Test with @Async + Awaitility (if async behavior required)**

```java
@SpringBootTest
class VocabImportServiceAsyncTest {
    
    @Autowired
    private VocabImportService service;
    
    @Autowired
    private VocabWordRepository wordRepo;
    
    @Test
    void shouldCompleteAsyncImportWithinTimeout() {
        service.importCsvAsync(new FileInputStream("vocab.csv"));
        
        await()
            .atMost(Duration.ofSeconds(5))
            .pollDelay(Duration.ofMillis(100))
            .untilAsserted(() -> {
                assertThat(wordRepo.count()).isEqualTo(3);
            });
    }
}
```

**Override executor in tests (sync execution):**

```java
@TestConfiguration
class TestAsyncConfig {
    @Bean
    public Executor taskExecutor() {
        return new SyncTaskExecutor();  // Runs tasks synchronously
    }
}

@SpringBootTest
@Import(TestAsyncConfig.class)
class VocabImportSyncExecTest {
    @Test
    void shouldImportSynchronouslyWithTestExecutor() {
        service.importCsvAsync(file);  // Runs sync due to test executor
    }
}
```

---

## Summary Table

| Pattern | Spring Overhead | Speed | Use For | Tool |
|---------|-----------------|-------|---------|------|
| Mockito unit test | None | ~1ms | Service logic, domain | `@ExtendWith(MockitoExtension.class)` |
| @WebMvcTest | Minimal (web layer) | ~50ms | Controller, auth flow | `@WebMvcTest + MockMvc` |
| @DataJpaTest | Moderate (DB + JPA) | ~500ms | Repository queries | `@DataJpaTest + Testcontainers` |
| @SpringBootTest | Full context | ~2s | End-to-end OAuth2 flow | `@SpringBootTest + @ServiceConnection` |
| Parameterized JUnit | None | ~1ms | Pure functions (SM2) | `@ParameterizedTest + @CsvSource` |

---

## Unresolved Questions

1. **Flyway schema versioning in tests:** Does Engly use Flyway in `@DataJpaTest` to auto-migrate schemas, or does it rely on `@Sql` annotations?
2. **OAuth2 issuer-uri in CI/CD:** When running tests in GitHub Actions, how is the OPEN-GATE issuer-uri mocked to avoid external dependency?
3. **Testcontainers parallel execution:** With multiple `@SpringBootTest` classes using Postgres containers, does container reuse need explicit configuration (e.g., `testcontainers.properties`)?

---

**Sources:**
- [Improved Testcontainers Support in Spring Boot 3.1](https://spring.io/blog/2023/06/23/improved-testcontainers-support-in-spring-boot-3-1/)
- [Testcontainers Spring Boot Reference](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Spring Boot 3.4 Released](https://devjava.substack.com/p/spring-34-now-available)
- [Zalando Engineering: Testing Efficiency](https://engineering.zalando.com/posts/2023/11/mastering-testing-efficiency-in-spring-boot-optimization-strategies-and-best-practices.html)
- [Spring Security Test Basics](https://www.arhohuttunen.com/spring-security-testing/)
- [JUnit 5 Parameterized Tests](https://www.baeldung.com/parameterized-tests-junit-5)
- [MockMvc standaloneSetup with ControllerAdvice](https://www.codingeek.com/forum/673/add-spring-global-restexceptionhandler-standalone-controller-test-mockmvc)
