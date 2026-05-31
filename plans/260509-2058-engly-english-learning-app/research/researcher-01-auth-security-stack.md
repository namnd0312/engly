# Auth & Security Stack Research — Engly Platform

**Date:** 2026-05-09 | **Status:** Complete | **Scope:** Stateless JWT + OAuth2 + Rate Limiting

---

## Topic A: Spring Security 6 + JWT + OAuth2 (Google/GitHub) for Stateless API

### Recommendation
**Use `oauth2ResourceServer` for API validation + custom OAuth2 Credentials Provider for provider login; backend issues its own signed JWT (RS256 with public key rotation via JWK Set endpoint; HS256 acceptable for MVP but requires key sharing overhead).**

### Why
- `oauth2ResourceServer` validates bearer tokens via JWT signature against public keys, enabling stateless API protection.
- Custom OAuth2 flow gives explicit control: backend exchanges provider tokens for app JWT, avoiding external token dependency.
- RS256 aligns w/ Spring Security defaults; auto-rotates via issuer-uri discovery (jwks_url). HS256 simpler (single secret) but stateless secret distribution harder at scale.

### Implementation Sketch

**Dependencies:**
```xml
<!-- Spring Security 6 + OAuth2 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

**SecurityFilterChain Bean (Spring Boot 3.4):**
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
  http
    .csrf(csrf -> csrf.disable()) // Stateless API
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(authorize -> authorize
      .requestMatchers("/auth/login", "/auth/signup", "/auth/refresh", "/oauth2/callback").permitAll()
      .requestMatchers("/api/admin/**").hasRole("ADMIN")
      .anyRequest().authenticated()
    )
    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
    .cors(cors -> cors.configurationSource(corsConfigurationSource()));
  
  return http.build();
}

@Bean
public CorsConfigurationSource corsConfigurationSource() {
  CorsConfiguration config = new CorsConfiguration();
  config.setAllowedOrigins(Arrays.asList("http://localhost:3000", "https://engly.com"));
  config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE"));
  config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type"));
  config.setAllowCredentials(true);
  
  UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
  source.registerCorsConfiguration("/**", config);
  return source;
}
```

**JwtDecoder (RS256 w/ auto key rotation via issuer-uri):**
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://engly-auth.example.com
          # Spring auto-discovers jwks_url, validates sig, exp, nbf, iss
          jws-algorithms: RS256
```

**Email Verification Token (short-lived JWT, 15 min):**
- Issue separate JWT with `purpose: "email_verification"` claim.
- Verify on GET `/auth/verify?token=<jwt>`: decode, check expiry + purpose.
- Simpler than Redis key; embedded claims avoid DB lookup.

**Reset Password Token (same pattern, 30 min):**
- JWT with `purpose: "password_reset"` + `userId` claim.
- On POST `/auth/reset-password`: validate token, update password, revoke token by recording used jti in Redis blacklist (short 30-min TTL).

### Pitfalls
- **Clock skew:** Set `JwtTimestampValidator(Duration.ofSeconds(60))` default; mismatched clocks cause rejection.
- **Startup coupling:** JWK Set discovery deferred to first request (good); ensure issuer accessible from BE.
- **Algorithm mismatch:** JWKS endpoint must advertise RS256; HS256 needs secure key sharing (env var only, not code).
- **No automatic logout:** Stateless JWT can't revoke until expiry; store revoked jti in Redis for critical features (force-logout, password-reset used token).

### Refs
- [Spring Security 6 OAuth2 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Boot 3.4 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.4-Release-Notes)
- [Beyond Sessions: JWT + Spring Security 6](https://medium.com/@parakh300994/beyond-sessions-implementing-jwt-authentication-and-role-based-authorization-in-spring-boot-3-with-4b0d4185de28)

---

## Topic B: NextAuth.js (Auth.js v5) Bridging to Spring Boot Backend

### Recommendation
**Option 1 (Recommended for MVP): NextAuth Credentials provider calls BE `/auth/login` → stores BE JWT in NextAuth JWT session cookie; FE forwards as `Authorization: Bearer` to API. Callback-driven token refresh in `jwt()` callback checks expiry + calls `/auth/refresh` on BE.**

### Why
- **Separation of concerns:** BE owns auth & token issuance; FE orchestrates session lifecycle.
- **Stateless BE:** No NextAuth dependency coupling backend.
- **Callback refresh:** JWT callback auto-refreshes on session access (no manual trigger); transparent to components.
- **Type-safe:** Module augmentation customizes session object w/ userId, roles, accessToken.

### Implementation Sketch

**`auth.ts` (Auth.js v5 config):**
```typescript
import NextAuth, { JWT, Session } from "next-auth";
import Credentials from "next-auth/providers/credentials";

declare module "next-auth" {
  interface Session {
    user: { id: string; role: string };
    accessToken: string;
    refreshToken?: string;
    expiresAt: number;
  }
  interface JWT {
    accessToken: string;
    refreshToken?: string;
    expiresAt: number;
    userId: string;
  }
}

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Credentials({
      name: "Credentials",
      credentials: {
        email: { label: "Email", type: "email" },
        password: { label: "Password", type: "password" },
      },
      async authorize(credentials) {
        const res = await fetch("http://localhost:8080/auth/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            email: credentials.email,
            password: credentials.password,
          }),
        });
        if (!res.ok) return null;
        
        const { accessToken, refreshToken, expiresAt, userId } = await res.json();
        return {
          id: userId,
          email: credentials.email,
          accessToken,
          refreshToken,
          expiresAt,
        };
      },
    }),
  ],
  session: { strategy: "jwt" }, // v5 explicit
  callbacks: {
    async jwt({ token, user }) {
      if (user) {
        token.accessToken = user.accessToken;
        token.refreshToken = user.refreshToken;
        token.expiresAt = user.expiresAt;
        token.userId = user.id;
      }

      // Check if token expired
      if (Date.now() < token.expiresAt * 1000) {
        return token;
      }

      // Refresh token (expires in ~7 days; rotate on use)
      const res = await fetch("http://localhost:8080/auth/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: token.refreshToken }),
      });
      
      if (!res.ok) {
        // Refresh failed; trigger re-login on next access
        token.expiresAt = 0;
        return token;
      }

      const data = await res.json();
      return {
        ...token,
        accessToken: data.accessToken,
        refreshToken: data.refreshToken, // Rotated
        expiresAt: data.expiresAt,
      };
    },
    async session({ session, token }: { session: Session; token: JWT }) {
      session.user.id = token.userId;
      session.accessToken = token.accessToken;
      session.expiresAt = token.expiresAt;
      return session;
    },
  },
  pages: {
    signIn: "/login",
    error: "/error",
  },
});
```

**`middleware.ts` (App Router protection):**
```typescript
export const middleware = auth((req) => {
  const isAuth = !!req.auth?.user;
  const isAuthRoute = req.nextUrl.pathname.startsWith("/login") || 
                      req.nextUrl.pathname.startsWith("/signup");

  if (isAuthRoute && isAuth) {
    return Response.redirect(new URL("/dashboard", req.url));
  }

  if (!isAuthRoute && !isAuth) {
    return Response.redirect(new URL("/login", req.url));
  }
});

export const config = { matcher: ["/dashboard/:path*", "/profile/:path*", "/login", "/signup"] };
```

**API Route (wrapper for BE):**
```typescript
// app/api/user/route.ts
import { auth } from "@/auth";

export async function GET() {
  const session = await auth();
  if (!session?.accessToken) return Response.json({ error: "Unauthorized" }, { status: 401 });

  const res = await fetch("http://localhost:8080/api/user", {
    headers: { Authorization: `Bearer ${session.accessToken}` },
  });
  return res.json();
}
```

**Server Component (using `auth()`):**
```typescript
import { auth } from "@/auth";

export default async function DashboardPage() {
  const session = await auth();
  
  if (!session) {
    redirect("/login");
  }

  return <div>Welcome, {session.user.email}</div>;
}
```

### Pitfalls
- **JWT callback called on every session check:** Optimize refresh logic (cache, debounce BE call if recent).
- **Middleware can't hit DB:** Signature validation only; actual permissions check must happen in API route or RSC.
- **Cookie prefix changed v4→v5:** `authjs.*` not `next-auth.*`; update any hardcoded cookie references.
- **Credentials provider non-persistent:** Users not stored in NextAuth DB; rely on BE user table for persistence.

### Refs
- [Auth.js v5 Credentials Provider](https://authjs.dev/getting-started/providers/credentials)
- [Auth.js v5 NextAuth Integration](https://authjs.dev/reference/nextjs)
- [Refresh Token Rotation (Auth.js)](https://authjs.dev/guides/refresh-token-rotation)
- [Migrating to v5](https://authjs.dev/getting-started/migrating-to-v5)

---

## Topic C: Bucket4j Redis-backed Rate Limiting on Spring Boot 3

### Recommendation
**Bucket4j Spring Boot Starter with Redis backend; filter-level enforcement before JWT auth so public endpoints (signup, login) rate-limited by IP; authed endpoints by userId.**

### Why
- **Spring Boot Starter:** Declarative via properties; no filter code boilerplate.
- **Redis:** Distributed state across instances; atomic token deduction.
- **Before-auth filter:** Protects signup/login without user context; per-IP prevents brute-force.
- **After-auth by userId:** Authed endpoints limited per user to prevent individual abuse.

### Implementation Sketch

**Dependencies:**
```xml
<dependency>
  <groupId>com.gkatzioura</groupId>
  <artifactId>bucket4j-spring-boot-starter</artifactId>
  <version>3.1.0</version>
</dependency>
<dependency>
  <groupId>io.lettuce</groupId>
  <artifactId>lettuce-core</artifactId>
</dependency>
```

**`application.yml`:**
```yaml
bucket4j:
  enabled: true
  cache:
    type: redis
    redis:
      host: localhost
      port: 6379
  
  filters:
    - cache-name: buckets
      url: /auth/login
      rate-limit:
        - bandwidths:
          - capacity: 5
            time: 1
            unit: minutes
          key-resolver: ip # IP-based for public endpoint
      
    - cache-name: buckets
      url: /auth/signup
      rate-limit:
        - bandwidths:
          - capacity: 3
            time: 1
            unit: minutes
          key-resolver: ip
      
    - cache-name: buckets
      url: /auth/refresh
      rate-limit:
        - bandwidths:
          - capacity: 30
            time: 1
            unit: minutes
          key-resolver: user # User-based for authed endpoint
      
    - cache-name: buckets
      url: /auth/forgot-password
      rate-limit:
        - bandwidths:
          - capacity: 3
            time: 1
            unit: hours
          key-resolver: ip # Email enumeration protection
```

**Custom Key Resolver (user-based for authed endpoints):**
```java
@Configuration
public class RateLimitingConfig {
  
  @Bean(name = "user")
  public KeyResolver userKeyResolver() {
    return (request) -> {
      String userId = (String) request.getAttribute("userId"); // Set by filter after JWT validation
      return userId != null ? Mono.just(userId) : Mono.just("anonymous");
    };
  }

  @Bean(name = "ip")
  public KeyResolver ipKeyResolver() {
    return (request) -> {
      String ip = request.getRemoteAddress() != null 
        ? request.getRemoteAddress().getAddress().getHostAddress()
        : "unknown";
      return Mono.just(ip);
    };
  }
}
```

**Filter to extract userId into request attribute (post-JWT validation):**
```java
@Component
@Order(1) // Before RateLimitingFilter
public class UserIdExtractorFilter extends OncePerRequestFilter {
  
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) 
      throws ServletException, IOException {
    
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      request.setAttribute("userId", auth.getName()); // JWT subject (userId)
    }
    filterChain.doFilter(request, response);
  }
}
```

**Redis Configuration:**
```java
@Configuration
public class RedisConfig {
  
  @Bean
  public LettuceConnectionFactory connectionFactory() {
    return new LettuceConnectionFactory();
  }
  
  @Bean
  public RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory factory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    return template;
  }
  
  @Bean
  public CacheManager cacheManager(LettuceConnectionFactory factory) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofDays(7));
    return RedisCacheManager.create(factory);
  }
}
```

### Pitfalls
- **Key resolver mismatch:** IP-based for public endpoints (signup/login); userId-based for authed (refresh). Mixing causes cross-user rate-limit bleed.
- **Startup order:** Rate limiting filter must register before SecurityFilterChain; use `@Order` on custom filters.
- **Redis unavailability:** No fallback; configure Redis reconnection policy or health check.
- **Health/metrics endpoints:** Exclude from rate limiting (configure separate filter chain or bypass rule).

### Refs
- [Bucket4j Redis Rate Limiting (Spring Boot)](https://www.innoq.com/en/blog/2024/03/distributed-rate-limiting-with-spring-boot-and-redis/)
- [Bucket4j + Spring Boot Starter](https://dev.to/franciskinyuru/rate-limiting-with-bucket4j-redis-postgres-in-springboot-4p02)
- [Rate Limiting Spring APIs (Baeldung)](https://www.baeldung.com/spring-bucket4j)

---

## Decisions to Surface to Planner

1. **RS256 vs HS256:** Recommend RS256 w/ Spring auto-discovery (issuer-uri) for production readiness; HS256 acceptable for MVP (simpler, no JWKS endpoint).
2. **Email Verification Strategy:** Short-lived JWT (15 min) with `purpose` claim preferred over Redis key; decouples DB, leverages JWT expiry.
3. **Refresh Token Rotation:** Implement in NextAuth `jwt()` callback; transparent, no client code. Store refresh tokens in BE Redis w/ 7-day TTL + revocation list.
4. **Rate Limiting Granularity:** IP for public (signup/login), userId for authed (refresh, API). Cost: requires post-JWT filter to extract userId into request context.
5. **Session Strategy (FE):** NextAuth JWT session (not database); aligns w/ BE statelessness.

## Unresolved Questions

- **Token signing key rotation on BE:** Spring auto-handles if using issuer-uri + JWKS endpoint; custom signing key rotation strategy needed if HS256 (rotate env var? schedule job?).
- **OAuth2 provider flow detail:** Exact Google/GitHub exchange sequence (authorization_code flow, token endpoint) — defer to implementation phase; planner to specify.
- **Redis failover:** Single-node vs cluster for rate-limiting + refresh-token store? Arch decision for planner.
