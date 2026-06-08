package com.engly.vocab.imports;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Abstract base for Testcontainers-backed vocab import integration tests.
 * Provides shared Postgres container + datasource config + admin JWT helper.
 *
 * <p><b>Singleton container pattern (deliberate):</b> the container is started ONCE in a static
 * initializer and lives for the whole JVM; Testcontainers registers a JVM shutdown hook on
 * {@code start()} that stops it at graceful exit (Ryuk is disabled in this build — see pom
 * surefire {@code TESTCONTAINERS_RYUK_DISABLED}). It is deliberately NOT managed by
 * {@code @Testcontainers}/{@code @Container}, which would stop the container after each test
 * class. Because Spring caches the {@code @SpringBootTest} context and binds the
 * datasource URL via {@link DynamicPropertySource} at context-creation time, a per-class container
 * lifecycle leaves later classes (reusing the cached context) pointed at a dead port → HikariCP
 * "Connection refused"/30s timeout. One long-lived container keeps the cached context valid across
 * all import IT classes.
 *
 * Flyway V1–V5 migrations run once when the shared context is first created.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractVocabImportIntegrationTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Bearer JWT with ROLE_ADMIN for API tests. */
    protected static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * Poll a job until it reaches a terminal state (COMPLETED/FAILED).
     * @param jobId job ID to poll
     * @param repo autowired repository
     * @param timeoutSeconds max seconds to wait (throws AssertionError on timeout)
     * @return final job state
     */
    protected static void pollUntilTerminal(
            Long jobId,
            com.engly.vocab.imports.infrastructure.VocabImportRepository repo,
            long timeoutSeconds) throws InterruptedException {
        long deadlineMs = System.currentTimeMillis() + (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < deadlineMs) {
            var job = repo.findById(jobId);
            if (job.isPresent()) {
                var status = job.get().status();
                if (status == com.engly.vocab.imports.domain.ImportJobStatus.COMPLETED ||
                    status == com.engly.vocab.imports.domain.ImportJobStatus.FAILED) {
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + jobId + " did not reach terminal state within " +
                timeoutSeconds + " seconds");
    }
}
