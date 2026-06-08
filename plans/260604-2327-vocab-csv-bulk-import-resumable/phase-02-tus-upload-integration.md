# Phase 02 — tus Resumable Upload Integration

## Context Links
- [plan.md](plan.md) · [researcher-01 tus](research/researcher-01-tus-upload-integration.md) · [scout §6 SecurityConfig](scout/scout-01-vocab-and-admin-patterns.md)
- Modify: `engly-be/src/main/java/com/engly/common/SecurityConfig.java`, `engly-be/src/main/resources/application.yml`, `engly-be/pom.xml`

## Overview
- **Date:** 2026-06-04 · **Priority:** P2 (blocks job creation in phase-06) · **Status:** pending
- Wire `me.desair.tus:tus-java-server:1.0.0-3.0` as a disk-backed resumable upload endpoint at `/api/admin/vocab-imports/upload`, streaming chunks to `/data/uploads`, not RAM.

## Key Insights
- tus uses raw `PATCH` + `application/offset+octet-stream` — Spring multipart resolver MUST NOT intercept it.
- Chain 1 (`/api/admin/**`, Order 1) already enforces ROLE_ADMIN, stateless, CSRF disabled → upload path inherits it. No new security chain needed; only ensure tus PATCH/HEAD allowed (CSRF already off).
- tus lib buffers chunks to disk internally (ConfigurableChunkStore), not memory → safe at 512MB.
- Jakarta-compatible (Servlet 6), Java 17+ → fits Boot 3.4.5 / Java 21.
- `uploadId` = last path segment of upload URI; completion = `uploadedBytes == totalBytes`.

## Requirements
- **Functional:** POST (create), PATCH (chunk), HEAD (offset), DELETE (abort) all delegate to `tusService.process(req,resp)`. Resume across client disconnect.
- **Non-functional:** Stream to disk; max upload size configurable (default 5GB); long connection timeout for GB uploads.

## Architecture
```
Client (tus-js-client) → POST/PATCH/HEAD/DELETE /api/admin/vocab-imports/upload[/{id}]
   → VocabImportUploadController → TusFileUploadService.process() → /data/uploads/{uploadId}
Chain 1 security (ROLE_ADMIN, stateless, CSRF off) wraps the path.
```

## Related Code Files
- **Create:**
  - `engly-be/.../vocab/imports/upload/VocabImportUploadController.java` (<60 LOC; delegates 4 verbs)
  - `engly-be/.../vocab/imports/upload/TusUploadConfig.java` (<60 LOC; `TusFileUploadService` bean + storage path)
- **Modify:**
  - `engly-be/pom.xml` (+ tus-java-server; commons-io/lang3/codec are transitive but pin if needed)
  - `application.yml` (upload path, tomcat max-swallow-size, connection-timeout, multipart bypass note)
  - `SecurityConfig.java` ONLY if Chain 1 matcher needs to explicitly permit upload sub-path (likely already covered by `/api/admin/**`; verify, no edit if covered)
- **Delete:** none

## Implementation Steps
1. Add dependency to `pom.xml`:
   ```xml
   <dependency><groupId>me.desair.tus</groupId><artifactId>tus-java-server</artifactId><version>1.0.0-3.0</version></dependency>
   ```
2. `TusUploadConfig`: `@Bean TusFileUploadService` → `setStorageLocation(new File(${upload.storage.path:/data/uploads}))`, `setUploadURI("/api/admin/vocab-imports/upload")`, `setMaxUploadSize(${upload.max-size:5368709120})`.
3. `VocabImportUploadController` `@RestController @RequestMapping("/api/admin/vocab-imports/upload")`:
   - `@RequestMapping(method={POST,PATCH,HEAD,DELETE})` single handler OR 4 mapped methods, each `tusService.process(req,resp); return ResponseEntity.status(resp.getStatus()).build();` — also map sub-path `/**` (tus appends `/{uploadId}` to PATCH/HEAD).
4. `application.yml`:
   ```yaml
   upload:
     storage: { path: ${UPLOAD_DIR:/data/uploads} }
     max-size: 5368709120
   server:
     tomcat:
       max-swallow-size: -1        # do not cap swallow for huge bodies
       connection-timeout: 1800000 # 30m
   ```
5. Multipart bypass: tus content-type is `application/offset+octet-stream` → Spring's multipart resolver only triggers on `multipart/*`, so no interception. Add comment; do NOT register a global multipart filter on the upload path. Confirm via integration test (phase-08 tus test).
6. Verify Chain 1 covers `/api/admin/vocab-imports/upload/**` (it matches `/api/admin/**`). No SecurityConfig edit unless test shows 403 on PATCH (CSRF already disabled in Chain 1).
7. Compile: `./mvnw -pl engly-be -am compile`.

## Todo List
- [ ] Add tus dependency, `mvn dependency:tree` confirms commons-io present
- [ ] `TusUploadConfig` bean + configurable path/size
- [ ] Controller delegates POST/PATCH/HEAD/DELETE incl. `/{uploadId}` sub-path
- [ ] application.yml tomcat + upload props
- [ ] Confirm multipart resolver does not intercept offset+octet-stream
- [ ] Confirm Chain 1 admits PATCH without CSRF (no edit if green)
- [ ] Compile clean

## Success Criteria
- A tus client uploads a multi-MB file in chunks; interrupting + resuming continues from server offset (HEAD returns `Upload-Offset`).
- Finalized file present at `/data/uploads/{uploadId}`, size == declared.
- Non-admin Bearer → 403; missing token → 401.

## Risk Assessment
- **Storage location not writable / not persistent** → fail fast on bean init if dir not creatable; persistence is OPEN QUESTION #2.
- **Tomcat default max-swallow-size aborts large PATCH** → set `-1`.
- **Helper file count > 200 LOC** → keep config + controller separate (each <60 LOC).

## Security Considerations
- ROLE_ADMIN enforced by existing Chain 1; stateless Bearer; CSRF off (correct for bearer).
- `uploadId` is library-generated UUID → not attacker-chosen path; phase-06 still validates resolved file stays under storage root (path-traversal defense).
- `maxUploadSize` caps disk-exhaustion DoS.

## Next Steps
- Phase-06 `POST /api/admin/vocab-imports` calls `tusService.getUploadInfo()` to confirm completion + resolve `file_path`/`file_size` before creating job.
