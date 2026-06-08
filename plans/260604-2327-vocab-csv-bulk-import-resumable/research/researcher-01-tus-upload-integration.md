# TUS Resumable Upload Integration — Spring Boot 3.4.5 / Java 21

## 1. Maven Coordinates & Jakarta Compatibility

**Latest Stable Version:** `1.0.0-3.0` (MIT license, ~2yr maintained)

```xml
<dependency>
    <groupId>me.desair.tus</groupId>
    <artifactId>tus-java-server</artifactId>
    <version>1.0.0-3.0</version>
</dependency>
```

**Java Requirement:** Java 17+ (matches project Java 21 ✓)

**Jakarta EE Verdict:** ✓ COMPATIBLE. Requires Jakarta Servlet API 6.0.x (NOT javax.servlet). Spring Boot 3.x uses jakarta.* → full alignment.

**Dependencies:**
- apache commons-lang3 (3.7+)
- apache commons-io (2.6+)
- apache commons-codec (1.11+)
- SLF4J (1.7.25+)

Repository: https://github.com/tomdesair/tus-java-server

---

## 2. Minimal Spring Integration Wiring

**Approach:** Delegate via @RestController to TusFileUploadService.

```java
@RestController
@RequestMapping("/api/admin/vocab-imports/upload")
@RequiredArgsConstructor
public class VocabUploadController {
    
    private final TusFileUploadService tusService;
    
    @PostMapping
    public ResponseEntity<Void> handleUploadInit(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        tusService.process(req, resp);
        return ResponseEntity.status(resp.getStatus()).build();
    }
    
    @PatchMapping
    public ResponseEntity<Void> handleUploadChunk(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        tusService.process(req, resp);
        return ResponseEntity.status(resp.getStatus()).build();
    }
    
    @HeadMapping
    public ResponseEntity<Void> handleUploadStatus(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        tusService.process(req, resp);
        return ResponseEntity.status(resp.getStatus()).build();
    }
    
    @DeleteMapping
    public ResponseEntity<Void> handleUploadCancel(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        tusService.process(req, resp);
        return ResponseEntity.status(resp.getStatus()).build();
    }
}
```

**Alternative (Servlet-Filter approach):** Register a TusFileUploadFilter as a Bean — cleaner but requires intercepting raw requests.

---

## 3. Disk Storage Configuration

```java
@Configuration
public class TusUploadConfig {
    
    @Bean
    public TusFileUploadService tusFileUploadService(@Value("${upload.storage.path:/data/uploads}") String storagePath) {
        TusFileUploadService uploadService = new TusFileUploadService();
        uploadService.setStorageLocation(new File(storagePath));
        uploadService.setUploadURI("/api/admin/vocab-imports/upload");
        uploadService.setMaxUploadSize(5_368_709_120L); // 5GB max
        return uploadService;
    }
}
```

**application.yml:**
```yaml
upload:
  storage:
    path: /data/uploads  # or ${UPLOAD_DIR} env var
```

**Confirmation:** Chunks stream directly to disk via FileOutputStream (tus-java-server buffers internally in ConfigurableChunkStore, NOT in memory). Chunks written to temp file, moved on completion.

---

## 4. Upload Completion & File Retrieval

```java
// After client finishes sending all chunks (client POSTs final size via TUS protocol)
String uploadUri = extractUploadIdFromUri("api/admin/vocab-imports/upload/abc123");

UploadInfo info = tusService.getUploadInfo(uploadUri);
if (info.getUploadedBytes() == info.getTotalBytes()) {
    // Completed
    Path completedFile = Paths.get(tusService.getStorageLocation().getAbsolutePath(), uploadUri);
    try (InputStream is = Files.newInputStream(completedFile)) {
        // Stream to vocab CSV processor
        importVocabCSV(is);
    }
    // Extract uploadId: URI format is /upload/{uploadId}, extract last segment
    String uploadId = uploadUri.substring(uploadUri.lastIndexOf('/') + 1);
}
```

**Key Methods:**
- `TusFileUploadService.getUploadInfo(String uploadURI)` → UploadInfo
- `UploadInfo.getUploadedBytes()`, `.getTotalBytes()`
- `UploadInfo.getFile()` → File reference

---

## 5. Spring Security Integration (OAuth2 Bearer JWT)

**Challenge:** TUS uses raw PATCH + `application/offset+octet-stream` (NOT multipart/form-data).

**Spring Multipart Filter:** Must NOT intercept TUS traffic.

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/admin/vocab-imports/upload/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter()))
            )
            // Disable CSRF for TUS PATCH (stateless + Bearer token)
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/admin/vocab-imports/upload/**")
            );
        return http.build();
    }
    
    @Bean
    public JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles"); // or use 'scope'
            return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
        });
        return converter;
    }
}
```

**Multipart Config** — disable buffering for upload path:
```java
@Bean
public MultipartConfigElement multipartConfigElement() {
    MultipartConfigFactory factory = new MultipartConfigFactory();
    factory.setMaxFileSize("5GB");
    factory.setMaxRequestSize("5GB");
    // TUS traffic bypasses multipart resolver via content-type check
    return factory.createMultipartConfig();
}
```

**Tomcat Settings** (application.yml):
```yaml
server:
  tomcat:
    max-swallow-size: 5GB  # Allow large request bodies
    connection-timeout: 30m  # Long uploads need timeout buffer
    threads:
      max: 200
```

---

## 6. Cleanup Strategy

```java
@Component
@Scheduled(fixedRate = 3600000) // Run hourly
public class UploadCleanupTask {
    
    private final TusFileUploadService tusService;
    private static final long EXPIRY_MS = 86400000; // 24 hours
    
    @Scheduled(initialDelay = 60000, fixedRate = 3600000)
    public void cleanupExpiredUploads() {
        tusService.cleanup(EXPIRY_MS);
        // Alternative: manual deletion of stale upload temp files
        // File storageDir = new File("/data/uploads");
        // for each file.lastModified() > 24h ago → delete
    }
}
```

**Manual Cleanup:**
```java
File storageLocation = tusService.getStorageLocation();
File[] uploads = storageLocation.listFiles();
long now = System.currentTimeMillis();
for (File upload : uploads) {
    if (now - upload.lastModified() > 86400000) {
        FileUtils.deleteQuietly(upload);
    }
}
```

---

## Code Skeleton Summary

**Maven Dependency:** `1.0.0-3.0` (Jakarta-compatible, Java 17+)

**Spring Wiring:** @RestController delegating to `tusService.process(req, resp)` for POST/PATCH/HEAD/DELETE

**Storage:** Disk-backed ConfigurableChunkStore, configurable path via `@Value`, streams to disk not RAM

**Completion Detection:** `getUploadInfo()` + compare uploadedBytes == totalBytes

**Security:** OAuth2 Bearer JWT with ROLE_ADMIN, CSRF disabled for /upload/**, multipart filter bypass via content-type

**Cleanup:** @Scheduled task calling `tusService.cleanup()` or manual temp file pruning

---

## Unresolved Questions

1. **tus-java-server.jar size & performance overhead** — library footprint in Spring Boot 3 app? Any startup latency?
2. **Concurrent upload limits** — does TusFileUploadService support configurable max concurrent uploads? Thread pool sizing?
3. **Storage location permissions** — does library handle file permission errors gracefully on read-only filesystems?
4. **Integration with Spring's file upload validation** — how to hook custom virus scanning / file type validation into tus-java-server's completion flow?
5. **Resume token persistence** — does uploadId remain stable across server restarts, or must client re-upload on crash?
6. **Large file reassembly performance** — if combining 100k+ chunks, is there a perf cliff? Suggest chunk size limits in docs?
