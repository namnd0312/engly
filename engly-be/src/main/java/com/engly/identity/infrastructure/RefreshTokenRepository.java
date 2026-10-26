package com.engly.identity.infrastructure;

import com.engly.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revoke all of a user's live tokens (logout / credential change). */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    void revokeAllForUser(@Param("userId") UUID userId);

    /**
     * Set the step-up flag only on a still-live token owned by {@code userId}. A conditional UPDATE
     * (instead of load-modify-save) never rewrites {@code revoked}, so it cannot undo a concurrent
     * rotation or logout. Returns 1 when applied, 0 when the token is revoked/expired/foreign.
     */
    @Modifying
    @Query("update RefreshToken t set t.mfaVerified = true where t.tokenHash = :hash and t.userId = :userId "
            + "and t.revoked = false and t.expiresAt > :now")
    int markMfaVerifiedIfActive(@Param("hash") String tokenHash, @Param("userId") UUID userId,
                                @Param("now") java.time.Instant now);

    /** Drop the step-up from every live chain of a user (2FA disabled); next refresh mints mfa=false. */
    @Modifying
    @Query("update RefreshToken t set t.mfaVerified = false where t.userId = :userId and t.mfaVerified = true")
    int clearMfaVerifiedForUser(@Param("userId") UUID userId);
}
