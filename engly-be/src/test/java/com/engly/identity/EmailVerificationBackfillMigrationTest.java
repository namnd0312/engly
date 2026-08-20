package com.engly.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V11's backfill (see V11__create_email_verification_token.sql) is what prevents the new login
 * gate from locking out every pre-existing password account: without it, every account created
 * before this feature shipped is {@code email_verified=false} and would 403 on next login. Runs
 * Flyway directly against a real Postgres (no Spring context needed) so it can pause at V10 —
 * seed pre-feature fixture rows — then apply V11 and assert the backfill.
 */
@Testcontainers
@Tag("integration")
class EmailVerificationBackfillMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v11Backfill_verifiesExistingPasswordAccounts_leavesSsoAccountsUntouched() throws Exception {
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String password = postgres.getPassword();

        // 1. Migrate only up to V10 — the schema as it existed immediately before this feature.
        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .target("10")
                .load()
                .migrate();

        // 2. Seed fixture rows representing the pre-feature user base: a password account (must
        // be backfilled) and a passwordless SSO account (must be left untouched).
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            insertUser(conn, "local-password-fixture", "password-fixture@example.com", "bcrypt-hash", false);
            insertUser(conn, "sso-fixture", "sso-fixture@example.com", null, false);
        }

        // 3. Apply V11 (backfill + new table).
        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // 4. Assert: password account backfilled to verified; SSO account untouched.
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            assertThat(emailVerified(conn, "local-password-fixture")).isTrue();
            assertThat(emailVerified(conn, "sso-fixture")).isFalse();
        }
    }

    private static void insertUser(Connection conn, String sub, String email, String passwordHash,
                                   boolean emailVerified) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO app_user (sub, email, password_hash, email_verified) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, sub);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setBoolean(4, emailVerified);
            ps.executeUpdate();
        }
    }

    private static boolean emailVerified(Connection conn, String sub) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT email_verified FROM app_user WHERE sub = ?")) {
            ps.setString(1, sub);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row for sub=" + sub).isTrue();
                return rs.getBoolean("email_verified");
            }
        }
    }
}
