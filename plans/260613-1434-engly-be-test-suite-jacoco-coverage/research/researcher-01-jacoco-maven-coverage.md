# JaCoCo Maven Coverage Integration Research

**Project:** Engly Backend (Spring Boot 3.4.5 / Java 21 / Maven)  
**Date:** 2026-06-13  
**Scope:** JaCoCo plugin compatibility, pom.xml configuration, argLine propagation, exclude patterns, threshold recommendations.

---

## 1. JaCoCo Plugin Version & Spring Boot 3.4.5 Compatibility

**Recommendation:** `0.8.11` (latest stable, released 2024-01-31)

- Spring Boot 3.4.5 pom uses `spring-boot-starter-parent` (parent POM); **spring-boot-starter-parent does NOT manage jacoco-maven-plugin version** — must pin explicitly.
- JaCoCo 0.8.11 explicitly supports Java 21 (tested at GA release).
- Tested with Maven 3.8.1+; no compatibility issues with Java 21 toolchain.

**DO NOT use 0.8.16-SNAPSHOT** — unstable; use released 0.8.11 or newer stable.

---

## 2. Complete pom.xml Plugin Configuration

Insert in `<build><plugins>` section AFTER maven-surefire-plugin:

```xml
<!-- JaCoCo Code Coverage -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <!-- Prepare agent for unit tests (maven-surefire-plugin) -->
        <execution>
            <id>prepare-agent</id>
            <phase>initialize</phase>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
            <configuration>
                <destFile>${project.build.directory}/jacoco.exec</destFile>
                <append>true</append>
            </configuration>
        </execution>
        <!-- Generate XML + HTML report (bound to verify phase) -->
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals>
                <goal>report</goal>
            </goals>
            <configuration>
                <dataFile>${project.build.directory}/jacoco.exec</dataFile>
                <outputDirectory>${project.build.directory}/site/jacoco</outputDirectory>
                <excludes>
                    <!-- DTOs, API models -->
                    <exclude>com/engly/**/dto/**</exclude>
                    <exclude>**/*Response</exclude>
                    <exclude>**/*Request</exclude>
                    <!-- Spring Config classes -->
                    <exclude>**/SecurityConfig</exclude>
                    <exclude>**/AsyncConfig</exclude>
                    <exclude>**/TusUploadConfig</exclude>
                    <exclude>**/OidcRoleMappingConfig</exclude>
                    <!-- Main application entry -->
                    <exclude>**/EnglyApplication</exclude>
                </excludes>
            </configuration>
        </execution>
        <!-- Coverage gate (fail build if thresholds not met) -->
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <dataFile>${project.build.directory}/jacoco.exec</dataFile>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <excludes>
                            <exclude>com/engly/**/dto/**</exclude>
                            <exclude>**/*Response</exclude>
                            <exclude>**/*Request</exclude>
                            <exclude>**/SecurityConfig</exclude>
                            <exclude>**/AsyncConfig</exclude>
                            <exclude>**/TusUploadConfig</exclude>
                            <exclude>**/OidcRoleMappingConfig</exclude>
                            <exclude>**/EnglyApplication</exclude>
                        </excludes>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.65</minimum>
                            </limit>
                            <limit>
                                <counter>BRANCH</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.55</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## 3. argLine Propagation with maven-surefire-plugin

**Critical Issue:** JaCoCo's `prepare-agent` goal injects Java agent via `${argLine}` property. Surefire must not hardcode `<argLine>` in config.

**Current pom.xml surefire config is safe:**
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <api.version>1.44</api.version>
        </systemPropertyVariables>
        <environmentVariables>
            <TESTCONTAINERS_RYUK_DISABLED>true</TESTCONTAINERS_RYUK_DISABLED>
        </environmentVariables>
    </configuration>
</plugin>
```

✓ No hardcoded `<argLine>` → JaCoCo's prepare-agent can safely inject its agent via `${argLine}`.  
✓ systemPropertyVariables & environmentVariables remain intact during coverage runs.

**Gotcha:** If surefire adds `<argLine>` hardcoded in the future, JaCoCo agent will not be appended. Solution: use Maven property syntax:
```xml
<argLine>${argLine} -Xmx512m</argLine>
```

---

## 4. Exclude Patterns Syntax

Both `<report>` and `<check>` use **Ant-style glob patterns**:

| Pattern | Matches |
|---------|---------|
| `com/engly/**/dto/**` | Any class under dto/ at any depth (e.g., `com/engly/vocab/dto/VocabResponse.class`) |
| `**/*Response` | Any class ending with "Response" (e.g., `SearchResponse`, `VocabResponse`) |
| `**/SecurityConfig` | Any class named `SecurityConfig` (e.g., `com/engly/security/SecurityConfig`) |
| `**/EnglyApplication` | Main Spring Boot application entry point |

**Recommended excludes (per project structure):**
- `com/engly/**/dto/**` — all data transfer objects
- `**/*Response` — API response models
- `**/*Request` — API request DTOs
- `**/config/**` — Spring config classes (alternative to individual configs)
- `**/EnglyApplication` — main entry
- `**/package-info` — Java package-info classes (if any)

---

## 5. Realistic Coverage Threshold

**Proposal:** LINE 65%, BRANCH 55% (BUNDLE scope)

**Justification:**
- Your codebase has partial test coverage being expanded (test suite in progress).
- Line 65%: Covers core business logic paths; excludes scaffolding, low-priority utility methods.
- Branch 55%: Enforces conditional logic testing; allows for edge cases deferred to Phase 2.
- BUNDLE scope: Single, project-wide gate; simpler to maintain than per-package rules.

**Progression path:**
- Baseline (Phase 1): LINE 65%, BRANCH 55%
- Phase 2 (3 months): LINE 75%, BRANCH 65%
- Phase 3 (6 months): LINE 85%, BRANCH 75%

**Scoping options:**
- `BUNDLE` — entire project (recommended, simplest)
- `PACKAGE` — per-package thresholds (e.g., services at 80%, repos at 70%)
- `CLASS` — per-class (granular, brittle; avoid)

**Counter explanations:**
- `COVEREDRATIO` — (covered / total). Use for percentage thresholds.
- `TOTALCOUNT` — absolute line/branch count (rare, use for size gates).

---

## 6. Generate & View Report + Verify Gate

**Generate report & run gate:**
```bash
cd /Users/admin/Desktop/DEV/AI/Engly/engly-be
./mvnw verify
```

**Flow:**
1. `initialize` phase → JaCoCo `prepare-agent` injects Java agent, sets `${argLine}`
2. `test` phase → Maven Surefire runs tests with coverage agent; writes `target/jacoco.exec`
3. `verify` phase → JaCoCo `report` generates `target/site/jacoco/index.html`; `check` validates thresholds
4. If coverage < thresholds → BUILD FAILS (exit code 1)

**View HTML report:**
```bash
open target/site/jacoco/index.html  # macOS
# or
xdg-open target/site/jacoco/index.html  # Linux
```

**Report structure:**
- `index.html` — project summary (total %, per-package breakdown)
- `com/engly/xxx/` — package details
- Class-level HTML — source code with line/branch coverage highlighting (green = covered, red = missed)

**Troubleshoot coverage not recorded:**
```bash
# Check jacoco.exec was created:
ls -lh target/jacoco.exec

# Verify surefire ran with agent (check Surefire output for "jacoco"):
./mvnw test -X 2>&1 | grep -i jacoco

# If forkCount=0 or forkMode=never in surefire config → agent not injected
# (your config is safe)
```

---

## Unresolved Questions

1. **Package-level thresholds:** Should `com.engly.vocab.*` (service layer) have stricter LINE 75% vs. `com.engly.api.*` (REST controllers) at LINE 60%? Recommend addressing in Phase 2 implementation.

2. **Integration test coverage:** Should `prepare-agent-integration` be added for failsafe tests (if integration tests expand)? Currently only unit tests measured.

3. **Pre-commit hook integration:** Should JaCoCo check be added to pre-commit hook to fail early, or only in CI/CD verify phase?

