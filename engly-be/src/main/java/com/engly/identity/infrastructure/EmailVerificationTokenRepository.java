package com.engly.identity.infrastructure;

import com.engly.identity.domain.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    Optional<EmailVerificationToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Atomic single-use claim: only one concurrent caller can flip an unconsumed, unexpired token
     * to consumed. 1 row affected = success, 0 = already used/expired/unknown — no read-then-write
     * race is possible.
     */
    @Modifying
    @Query("update EmailVerificationToken t set t.consumedAt = :now "
            + "where t.tokenHash = :hash and t.consumedAt is null and t.expiresAt > :now")
    int consumeIfUsable(@Param("hash") String hash, @Param("now") Instant now);

    /** Invalidate (delete) a user's prior tokens by DELETE — keeps the table bounded to ~1 row/user. */
    int deleteByUserId(UUID userId);
}
