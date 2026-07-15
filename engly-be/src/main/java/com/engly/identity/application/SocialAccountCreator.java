package com.engly.identity.application;

import com.engly.identity.domain.UserAccount;
import com.engly.identity.domain.UserIdentity;
import com.engly.identity.infrastructure.UserAccountRepository;
import com.engly.identity.infrastructure.UserIdentityRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates a brand-new social account + its provider link in an isolated {@code REQUIRES_NEW}
 * transaction. Isolation matters: on a concurrent first login the unique constraint
 * ({@code uq_identity_provider_subject} / {@code uq_app_user_email}) fires and marks THIS transaction
 * rollback-only. Running it in its own transaction means only this insert rolls back, leaving the
 * caller's transaction clean to re-resolve — catching the violation inside a shared transaction would
 * instead die at commit with {@code UnexpectedRollbackException}.
 *
 * <p>Separate bean (not a private method on {@link SocialLoginService}) so the {@code REQUIRES_NEW}
 * proxy actually applies — self-invocation would bypass it.
 */
@Component
public class SocialAccountCreator {

    private final UserAccountRepository users;
    private final UserIdentityRepository identities;

    public SocialAccountCreator(UserAccountRepository users, UserIdentityRepository identities) {
        this.users = users;
        this.identities = identities;
    }

    /**
     * New social user (fresh sub, no password) + its provider identity link.
     *
     * @param accountEmail  email to set on the new {@code app_user} — null when the provider email is
     *                      absent or already owned by another account (the unique {@code app_user.email}
     *                      forbids duplicating it; the caller decides).
     * @param identityEmail email seen from the provider, recorded on the identity row for audit (may be null).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAccount create(String provider, String providerUserId, String accountEmail,
                              String identityEmail, String displayName, boolean emailVerified) {
        UserAccount user = users.save(
                UserAccount.ssoUser("sso-" + UUID.randomUUID(), accountEmail, displayName, emailVerified));
        identities.save(UserIdentity.link(user.getId(), provider, providerUserId, identityEmail));
        return user;
    }
}
