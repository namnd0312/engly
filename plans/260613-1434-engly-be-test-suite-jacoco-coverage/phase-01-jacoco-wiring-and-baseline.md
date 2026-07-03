# Phase 01 — Audit Baseline + JaCoCo Wiring + Verify Gate

## Context Links
- JaCoCo research (exact pom XML, version, excludes, thresholds): `research/researcher-01-jacoco-maven-coverage.md`
- Scout gap map (summary table §STEP 6): `scout/scout-01-coverage-gap-map.md`
- Target pom: `/Users/admin/Desktop/DEV/AI/Engly/engly-be/pom.xml`

## Overview
- **Date:** 2026-06-13
- **Description:** Add `jacoco-maven-plugin` 0.8.11 to `engly-be/pom.xml` (prepare-agent + report + check), wire `./mvnw verify` as the coverage gate, capture a baseline coverage number, and confirm Docker/Testcontainers prerequisites.
- **Priority:** P1 (blocks measurement for all later phases)
- **Implementation status:** pending
- **Review status:** pending

## Key Insights
- Boot's `spring-boot-starter-parent` does NOT manage the JaCoCo plugin version → must pin `0.8.11` explicitly (Java 21 supported, stable GA).
- Current surefire config has NO hardcoded `<argLine>` → JaCoCo `prepare-agent` can inject its agent via `${argLine}` safely. MUST NOT add a hardcoded `<argLine>`; if ever needed use `<argLine>@{argLine} -Xmx...</argLine>`.
- `prepare-agent` bound to `initialize`, `report` + `check` bound to `verify`. Single `target/jacoco.exec`.
- Testcontainers integration tests (`StudyApiIntegrationTest`, all `vocab/imports/*IT`, verb IT) run during `test` phase → **Docker (OrbStack) MUST be running** or `./mvnw verify` fails before JaCoCo even reports. This is a hard prerequisite, not a test flake.
- Surefire already sets `api.version=1.44` + `TESTCONTAINERS_RYUK_DISABLED=true` for OrbStack — keep intact.

## Requirements
**Functional**
- `./mvnw verify` produces `target/jacoco.exec`, HTML at `target/site/jacoco/index.html`, and runs the `check` gate.
- Gate fails build (exit ≠ 0) when BUNDLE LINE < 65% or BRANCH < 55% (after excludes).
- Excludes applied identically in BOTH `report` and `check` executions.

**Non-functional**
- No change to existing surefire OrbStack config. No new hardcoded `<argLine>`.

## Architecture
Maven lifecycle: `initialize` (prepare-agent sets `${argLine}`) → `test` (surefire forks JVM with agent, writes exec) → `verify` (report generates HTML/XML, check validates thresholds, fails on miss).

## Related Code Files
**Modify**
- `/Users/admin/Desktop/DEV/AI/Engly/engly-be/pom.xml` — add jacoco plugin in `<build><plugins>` AFTER `maven-surefire-plugin`.

**Create** — none (config only).

## Implementation Steps
1. Insert the JaCoCo plugin block (verbatim from research §2) into `engly-be/pom.xml` `<build><plugins>` after the surefire plugin:
   ```xml
   <plugin>
       <groupId>org.jacoco</groupId>
       <artifactId>jacoco-maven-plugin</artifactId>
       <version>0.8.11</version>
       <executions>
           <execution>
               <id>prepare-agent</id>
               <phase>initialize</phase>
               <goals><goal>prepare-agent</goal></goals>
               <configuration>
                   <destFile>${project.build.directory}/jacoco.exec</destFile>
                   <append>true</append>
               </configuration>
           </execution>
           <execution>
               <id>report</id>
               <phase>verify</phase>
               <goals><goal>report</goal></goals>
               <configuration>
                   <dataFile>${project.build.directory}/jacoco.exec</dataFile>
                   <outputDirectory>${project.build.directory}/site/jacoco</outputDirectory>
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
               </configuration>
           </execution>
           <execution>
               <id>check</id>
               <phase>verify</phase>
               <goals><goal>check</goal></goals>
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
                               <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.65</minimum></limit>
                               <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.55</minimum></limit>
                           </limits>
                       </rule>
                   </rules>
               </configuration>
           </execution>
       </executions>
   </plugin>
   ```
2. Confirm Docker/OrbStack running: `docker ps` (prerequisite for Testcontainers).
3. **Baseline run with gate temporarily relaxed:** set `check` minimums to `0.0` (or run `./mvnw clean test org.jacoco:jacoco-maven-plugin:report`) to obtain the current BUNDLE LINE/BRANCH % WITHOUT failing the build. Record numbers from `target/site/jacoco/index.html`.
4. Restore thresholds to 0.65 / 0.55. If baseline already exceeds these, note it (phase-06 may raise the gate to baseline + margin).
5. Run `cd engly-be && ./mvnw clean verify` once to confirm the wired gate executes (may FAIL on threshold until phases 02–05 land — that is expected and acceptable for this phase's exit).

## Todo List
- [ ] Add JaCoCo plugin block to `engly-be/pom.xml` after surefire
- [ ] Verify excludes identical in `report` + `check`
- [ ] Confirm Docker/OrbStack running (`docker ps`)
- [ ] Capture baseline LINE/BRANCH % (relaxed gate)
- [ ] Restore thresholds 0.65 / 0.55
- [ ] Confirm `target/jacoco.exec` + `target/site/jacoco/index.html` generated
- [ ] Document baseline number in phase-06 / plan unresolved-questions

## Success Criteria
- `./mvnw verify` triggers prepare-agent → exec written → report + check run.
- HTML report present at `target/site/jacoco/index.html` with package breakdown.
- Baseline coverage % recorded. Gate at 65/55 in pom.
- No regression in existing 23 test files (all still compile + run).

## Risk Assessment
- **Docker not running** → all Testcontainers tests fail in `test` phase before JaCoCo reports. Mitigation: `docker ps` precheck; surface as documented prerequisite.
- **Hardcoded argLine added later** silently disables agent. Mitigation: comment in pom; `./mvnw test -X | grep -i jacoco` to verify agent injected.
- **Exclude pattern drift** between report/check → misleading gate. Mitigation: keep blocks byte-identical.
- **Baseline below 65/55** → gate red until phases land. Mitigation: expected; phases 02–05 close the gap; do not lower scope of excludes to game it.

## Security Considerations
- None new. JaCoCo is build-time only; `target/` already git-ignored (confirm). No secrets in reports.

## Next Steps
- Proceed to phases 02–04 (parallelizable) to raise coverage; phase-06 re-runs the gate green.
