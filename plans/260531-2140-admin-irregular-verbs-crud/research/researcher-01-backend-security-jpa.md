# Backend Implementation Research: Admin CRUD for IrregularVerb
**Status:** Complete | **Focus:** Role mapping, method security, entity mutation, CSRF flow, unique-constraint handling

---

## 1. Role Mapping from OIDC Claim (Session/OAuth2Login)

**Architecture:** Spring Security's `oauth2Login` with session cookie (no exposed tokens) requires a custom `OidcUserService` bean to map IdP roles → GrantedAuthorities.

**Implementation Pattern:**
```java
@Configuration
public class OidcSecurityConfig {
    @Bean
    public OidcUserService oidcUserService(ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOidcUserService delegate = new DefaultOidcUserService();
        
        return new OidcUserService() {
            @Override
            public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
                OidcUser oidcUser = delegate.loadUser(userRequest);
                
                // Map roles claim to GrantedAuthorities
                Collection<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());
                String rolesClaimName = "roles"; // configurable via application.yml
                
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) oidcUser.getAttributes().getOrDefault(rolesClaimName, List.of());
                
                roles.stream()
                     .map(role -> "ROLE_" + role.toUpperCase()) // prefix for hasRole()
                     .map(SimpleGrantedAuthority::new)
                     .forEach(authorities::add);
                
                return new DefaultOidcUser(authorities, oidcUser.getIdToken());
            }
        };
    }
}
```

**Why Custom Service:** `oauth2Login` doesn't auto-map user attributes to authorities; the delegate pattern allows minimal wrapping of `DefaultOidcUserService` while preserving standard OIDC token handling (ID token validation, claim access).

**Configuration (application.yml):**
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          open-gate:
            # ... existing config ...
            scope: openid,profile,email,roles  # request roles claim
  application:
    security:
      roles-claim-name: "roles"  # or "groups", "authorities" — app-configurable
```

**Prefix to ROLE_:** Spring's `hasRole('ADMIN')` auto-checks for `ROLE_ADMIN` authority; manual prefix ensures compatibility.

---

## 2. Method Security: URL vs. Method-Level Authorization

**Recommendation for Engly:** URL-based (`authorizeHttpRequests`) for POST/PUT/DELETE; keep simpler and cohesive with SecurityConfig.

**URL-Based Approach (Preferred):**
```java
// In SecurityConfig.securityFilterChain()
.authorizeHttpRequests(auth -> auth
    .requestMatchers(HttpMethod.GET, "/api/verbs/**").permitAll()
    .requestMatchers(HttpMethod.POST, "/api/verbs/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.PUT, "/api/verbs/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.DELETE, "/api/verbs/**").hasRole("ADMIN")
    // ... rest of config
)
```

**Rationale:**
- Centralized, transparent authorization rules (security config is the source of truth).
- No scattered `@PreAuthorize` annotations; easier to audit.
- Avoids method-level security framework overhead (proxy-based AOP).

**If Method-Level is Needed (e.g., service layer):**
```java
@EnableMethodSecurity(prePostEnabled = true) // add to SecurityConfig class
@Service
public class VerbService {
    @PreAuthorize("hasRole('ADMIN')")
    public IrregularVerb create(VerbRequest req) { ... }
}
```

**GET stays public:** Stateless; no change needed.

---

## 3. Exposing isAdmin in /api/me

**Update SessionController:**
```java
@RestController
public class SessionController {
    public record UserInfoResponse(
        String sub,
        String email,
        String name,
        boolean isAdmin
    ) {}

    @GetMapping("/api/me")
    public UserInfoResponse me(@AuthenticationPrincipal OidcUser principal) {
        // Check authorities for ROLE_ADMIN
        boolean isAdmin = principal.getAuthorities().stream()
            .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
        
        return new UserInfoResponse(
            principal.getSubject(),
            principal.getEmail(),
            principal.getFullName(),
            isAdmin
        );
    }
}
```

**Direct approach:** No SecurityContext lookup needed; `@AuthenticationPrincipal` provides the principal with its authorities.

---

## 4. CSRF Flow: CookieCsrfTokenRepository with Angular SPA

**Current Setup (Correct):**
- `CookieCsrfTokenRepository.withHttpOnlyFalse()` → XSRF-TOKEN cookie (readable by JS).
- `CsrfTokenRequestAttributeHandler` → plain handler (no double-submit; cookie value echoed as header).
- `CsrfCookieFilter` forces deferred token load → XSRF-TOKEN cookie issued on every response.

**Angular Client Flow:**
1. GET `/api/me` or any safe request → response includes XSRF-TOKEN cookie.
2. POST/PUT/DELETE: Angular's `HttpClientXsrfModule` auto-reads cookie → attaches header.
3. Header name: `X-XSRF-TOKEN` (Spring's default); cookie name: `XSRF-TOKEN`.

**No Backend Changes Needed for CSRF.** Existing SecurityConfig already handles it. Confirm Angular config:
```typescript
// In Angular HttpClientModule initialization
HttpClientXsrfModule.withOptions({
  cookieName: 'XSRF-TOKEN',
  headerName: 'X-XSRF-TOKEN'
})
```

**Caveats:** CsrfTokenRequestAttributeHandler is "plain" (not double-submit), safe for stateless + session hybrid. BREACH mitigation: not applicable here (httpOnly=false by design for SPA).

---

## 5. Mutating Immutable Entity: Setters vs. Builder vs. Update Method

**Current:** IrregularVerb is read-only (getters only). For CRUD, add setters — **KISS principle**.

**Strategy: Add Setters to IrregularVerb**
```java
// In IrregularVerb.java — add minimal mutators for admin operations
public void setBaseForm(String baseForm) { this.baseForm = baseForm; }
public void setPastSimple(String pastSimple) { this.pastSimple = pastSimple; }
public void setPastParticiple(String pastParticiple) { this.pastParticiple = pastParticiple; }
public void setPastSimpleAlt(String pastSimpleAlt) { this.pastSimpleAlt = pastSimpleAlt; }
public void setPastParticipleAlt(String pastParticipleAlt) { this.pastParticipleAlt = pastParticipleAlt; }
public void setIpaBase(String ipaBase) { this.ipaBase = ipaBase; }
public void setIpaPast(String ipaPast) { this.ipaPast = ipaPast; }
public void setIpaPp(String ipaPp) { this.ipaPp = ipaPp; }
public void setMeaningVi(String meaningVi) { this.meaningVi = meaningVi; }
public void setVerified(boolean verified) { this.verified = verified; }

// For JPA to generate id on insert
@GeneratedValue(strategy = GenerationType.IDENTITY)
private void setId(Long id) { this.id = id; }  // or keep @GeneratedValue public
```

**DTO for Requests:**
```java
public record VerbRequest(
    @NotBlank(message = "base_form is required")
    @Size(min = 1, max = 100, message = "base_form must be 1–100 chars")
    String baseForm,
    
    @NotBlank
    @Size(min = 1, max = 100)
    String pastSimple,
    
    @NotBlank
    @Size(min = 1, max = 100)
    String pastParticiple,
    
    @Size(max = 100)
    String pastSimpleAlt,
    
    @Size(max = 100)
    String pastParticipleAlt,
    
    @Size(max = 100)
    String ipaBase,
    
    @Size(max = 100)
    String ipaPast,
    
    @Size(max = 100)
    String ipaPp,
    
    @Size(max = 255)
    String meaningVi,
    
    boolean verified
) {}
```

**Mapping & Save:**
```java
@Service
public class VerbService {
    @Autowired
    private VerbJpaRepository repo;

    public IrregularVerb create(VerbRequest req) {
        IrregularVerb verb = new IrregularVerb();
        mapRequestToEntity(req, verb);
        try {
            return repo.save(verb);
        } catch (DataIntegrityViolationException e) {
            throw new UniqueConstraintViolationException("base_form must be unique");
        }
    }

    public IrregularVerb update(Long id, VerbRequest req) {
        IrregularVerb verb = repo.findById(id)
            .orElseThrow(() -> new VerbNotFoundException("Verb not found"));
        mapRequestToEntity(req, verb);
        try {
            return repo.save(verb);
        } catch (DataIntegrityViolationException e) {
            throw new UniqueConstraintViolationException("base_form must be unique");
        }
    }

    private void mapRequestToEntity(VerbRequest req, IrregularVerb verb) {
        verb.setBaseForm(req.baseForm());
        verb.setPastSimple(req.pastSimple());
        // ... etc
    }
}
```

**Exception Handling (GlobalExceptionHandler):**
```java
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<Map<String, Object>> handleUniqueConstraint(DataIntegrityViolationException ex) {
    String message = ex.getMessage().contains("base_form")
        ? "base_form must be unique"
        : "A database constraint was violated";
    return buildResponse(HttpStatus.CONFLICT, message);
}

// Custom exception for readability
public static class UniqueConstraintViolationException extends RuntimeException {
    public UniqueConstraintViolationException(String msg) { super(msg); }
}
```

---

## Summary

| Topic | Decision | Files to Modify/Create |
|-------|----------|----------------------|
| **Role Mapping** | Custom `OidcUserService` bean + configurable claim name | `OidcSecurityConfig.java` (create), `application.yml`, `SecurityConfig.java` |
| **Method Security** | URL-based `/api/verbs/**` POST/PUT/DELETE → `hasRole('ADMIN')` | `SecurityConfig.java` |
| **Admin Flag in /api/me** | Read `ROLE_ADMIN` from principal authorities | `SessionController.java` |
| **CSRF** | No change — existing flow works for Angular | (verify config, no backend code needed) |
| **Entity Mutation** | Add setters to `IrregularVerb`; create `VerbRequest` record | `IrregularVerb.java`, `VerbRequest.java` (create), `VerbService.java` (create), `GlobalExceptionHandler.java` |

---

## Unresolved Questions

1. **OPEN-GATE IdP roles claim shape:** Is it `roles: ["admin", "user"]` (array of strings) or `roles: { admin: true, user: true }` (object)? Current code assumes array; adjust if needed.
2. **Role name casing:** Will the IdP return "admin" or "ADMIN"? Code uses `.toUpperCase()` to normalize.
3. **Prefix strategy:** Confirm that `hasRole('ADMIN')` checks for `ROLE_ADMIN` in Spring Security 6 (it does; just verify against OPEN-GATE docs).
