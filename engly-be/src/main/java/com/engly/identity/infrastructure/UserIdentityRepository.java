package com.engly.identity.infrastructure;

import com.engly.identity.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /** Resolve a returning social login to its linked account. */
    Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);
}
