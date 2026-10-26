package com.engly.identity.infrastructure;

import com.engly.identity.domain.UserMfaTotp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserMfaTotpRepository extends JpaRepository<UserMfaTotp, UUID> {
    boolean existsByUserIdAndConfirmedAtIsNotNull(UUID userId);

    /**
     * Row-locked read (SELECT … FOR UPDATE). Every code check goes through this so parallel requests
     * for one user serialize: the failure counter cannot lose updates and one code cannot be accepted
     * twice by two concurrent transactions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from UserMfaTotp m where m.userId = :userId")
    Optional<UserMfaTotp> findForUpdate(@Param("userId") UUID userId);
}
