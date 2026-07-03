# Phase 04 — Identity + Common Slice Tests

## Context Links
- Scout cases: `scout/scout-01-coverage-gap-map.md` §§1,2 (SessionController, GlobalExceptionHandler) + §STEP 4 (exception→status table)
- Test strategy: `research/researcher-02-spring-test-strategy.md` §3 (@WithMockUser/jwt/oidcLogin), §5 (standaloneSetup ControllerAdvice)
- Targets: `engly-be/src/main/java/com/engly/identity/api/SessionController.java`, `engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java`
- Security pattern: `engly-be/src/test/java/com/engly/common/AdminSecurityRulesTest.java`

## Overview
- **Date:** 2026-06-13
- **Description:** Cover the only UNCOVERED controller (`SessionController`, BFF `/api/me` + `/api/token`) and the THIN `GlobalExceptionHandler` (all `@ExceptionHandler` mappings). SessionController via `@WebMvcTest` with session/OIDC postprocessor; handler via `standaloneSetup` MockMvc + dummy controller throwing each exception.
- **Priority:** P1
- **Implementation status:** complete
- **Review status:** pending

## Key Insights
- `SessionController` reads identity from `@AuthenticationPrincipal OidcUser` (Chain 2 session, NOT bearer). Use `oidcLogin()` postprocessor (as `StudyApiIntegrationTest` does) to inject subject/email/name/authorities; anonymous → 401.
- `@WebMvcTest(SessionController.class)` will pull `SecurityConfig` only if imported; to assert 401-for-anonymous accurately, `@Import({SecurityConfig.class, OidcRoleMappingConfig.class})` and mock `ClientRegistrationRepository` + `JwtDecoder` (mirror `AdminSecurityRulesTest`). Alternatively keep slice minimal and assert principal-mapping logic with `oidcLogin()` (auth always present) — pick the `@Import` route so 401 path is genuinely exercised.
- `isAdmin` is derived from authorities containing `ROLE_ADMIN` → test both with and without that authority on the oidcLogin token.
- `GlobalExceptionHandler` builds a `Map` body with `timestamp/status/error/message`. The cleanest unit test is `standaloneSetup(new DummyController()).setControllerAdvice(new GlobalExceptionHandler())` — zero Spring context, one dummy controller with endpoints that each throw a target exception. This directly exercises every handler method + `buildResponse`.
- `DataIntegrityViolationException` handler returns generic `"resource already exists"` (no SQL leak) and logs `warn` — assert body message is the generic string, NOT the cause.
- `ConstraintViolationException` handler strips method-path prefix to leaf param name — assert message has no `serviceName.method.` prefix.

## Requirements
**Functional** — all 8 SessionController cases + all 10 exception→status mappings (scout §STEP 4 table).
**Non-functional** — files < 200 LOC; SessionController slice uses `@MockitoBean` for any collaborators (none needed beyond security beans).

## Architecture
- `SessionControllerTest`: `@WebMvcTest(SessionController.class)` + `@Import({SecurityConfig, OidcRoleMappingConfig})` + `@MockitoBean ClientRegistrationRepository` + `@MockitoBean JwtDecoder`; MockMvc + `oidcLogin()`.
- `GlobalExceptionHandlerTest`: plain JUnit + `MockMvcBuilders.standaloneSetup(dummyController).setControllerAdvice(new GlobalExceptionHandler()).build()`. Static inner `@RestController` with one mapping per exception.

## Related Code Files
**Create**:
| Test file | Target class |
|-----------|--------------|
| `src/test/java/com/engly/identity/api/SessionControllerTest.java` | `com.engly.identity.api.SessionController` |
| `src/test/java/com/engly/common/GlobalExceptionHandlerTest.java` | `com.engly.common.GlobalExceptionHandler` |

**Modify / Delete** — none. (`SecurityRulesTest` already covers some handler paths via @WebMvcTest; this adds the dedicated unit coverage — DRY: assert mapping shape here, not security rules.)

## Implementation Steps
1. **SessionControllerTest** — cases:
   - GET `/api/me` with `oidcLogin().idToken(t -> t.subject("u").claims(c -> c.put("email","e@x"))...)` no admin authority → 200, `sub="u"`, `isAdmin=false`.
   - GET `/api/me` with authority `ROLE_ADMIN` → `isAdmin=true`.
   - GET `/api/me` anonymous → 401.
   - GET `/api/token` with oidcLogin (idToken tokenValue set) → 200, `token` == id_token value.
   - GET `/api/token` anonymous → 401.
   - GET `/api/me` principal without email claim → 200, `email` null (graceful).
   - GET `/api/me` principal without fullName → 200, `name` null (graceful).
   - (Optional) verify response is JSON with expected fields.
2. **GlobalExceptionHandlerTest** — dummy controller endpoints, one per row of scout §STEP 4:
   - `VerbNotFoundException` → 404, body.message == thrown message, body has timestamp/status/error.
   - `VocabDeckNotFoundException` → 404.
   - `VocabWordNotFoundException` → 404.
   - `VocabImportJobNotFoundException` → 404.
   - `VocabImportConflictException` → 409.
   - `IllegalArgumentException("page must be >= 0")` → 400, message preserved.
   - `MethodArgumentNotValidException` → 400, message `"field: msg"` (first error only). Build via a `@Valid` body on the dummy endpoint + invalid payload, OR construct the exception directly and call handler method.
   - `ConstraintViolationException` → 400, leaf-param message (no method-path prefix). Construct a violation set with propertyPath like `svc.method.size` → assert message starts `size:`.
   - `DataIntegrityViolationException("dup key sql...")` → 409, body.message == `"resource already exists"` (NO sql leak).
   - generic `Exception` → 500, body.message == `"An unexpected error occurred"`.
   - Assert every response body contains `timestamp`, `status`, `error`, `message`.
3. For `MethodArgumentNotValidException`/`ConstraintViolationException`, prefer the standaloneSetup route with a real `@Valid` dummy endpoint where feasible (more realistic); fall back to direct handler-method invocation if constructing the exception is simpler/cleaner (KISS).
4. Run: `cd engly-be && ./mvnw test -Dtest='SessionControllerTest,GlobalExceptionHandlerTest'`.

## Todo List
- [x] `SessionControllerTest` (8 cases, 401 path via @Import security)
- [x] `GlobalExceptionHandlerTest` (10 mappings + body-shape assertions)
- [x] Verify no SQL leak on DataIntegrityViolation case
- [x] Verify ConstraintViolation leaf-name stripping
- [x] All compile + pass; SessionControllerTest 152 LOC; GlobalExceptionHandlerTest 235 LOC (10 handlers + DummyController inherently verbose)

## Success Criteria
- Both files pass via `./mvnw test`.
- `SessionController` 100% line; `GlobalExceptionHandler` all handler methods + `buildResponse` covered (JaCoCo HTML).
- Anonymous 401 path genuinely exercised (not bypassed by always-authenticated postprocessor).

## Risk Assessment
- **@WebMvcTest component-scan** pulls other controllers' services → missing-bean failures. Mitigation: `@WebMvcTest(SessionController.class)` (explicit single controller) + `@Import` only security configs; add `@MockitoBean` for `ClientRegistrationRepository`/`JwtDecoder` as in `AdminSecurityRulesTest`.
- **MethodArgumentNotValidException construction** is awkward to build by hand. Mitigation: drive it through a real `@Valid` dummy endpoint with a bad body.
- **oidcLogin email/name claims** API shape. Mitigation: follow Spring Security test `oidcLogin().idToken(...)` builder; null-claim cases assert graceful null.

## Security Considerations
- Confirms BFF endpoints require auth (401 anonymous) and `isAdmin` derives strictly from `ROLE_ADMIN` authority — prevents privilege misreporting to the SPA.
- Confirms handler never leaks SQL/stack traces (DataIntegrity → generic; generic Exception → generic).

## Next Steps
- Independent of phases 02/03. Admin-endpoint RBAC (401 vs 403 on `/api/admin/**`) reinforced in phase-05. Feeds phase-06.
