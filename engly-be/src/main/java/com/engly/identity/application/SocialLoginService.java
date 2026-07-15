package com.engly.identity.application;

import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.domain.UserIdentity;
import com.engly.identity.infrastructure.UserAccountRepository;
import com.engly.identity.infrastructure.UserIdentityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Resolves a social login (Google/Facebook) to a local {@link UserAccount}, creating or linking as
 * needed. Implements the verified-both-sides linking policy:
 *
 * <ol>
 *   <li>Returning identity ({@code (provider, providerUserId)} match) → its account.</li>
 *   <li>Google with a provider-verified email that matches an existing <b>already-verified</b> account
 *       → link (attach a {@link UserIdentity} row). A match against an <b>unverified</b> account
 *       (e.g. a password account — password signup never verifies email ownership) is NOT merged, to
 *       prevent pre-account-hijacking of a squatted email.</li>
 *   <li>Otherwise (Facebook always, or Google without a verified email) → a fresh account keyed on the
 *       provider id; email is stored if present (unverified) or null.</li>
 * </ol>
 *
 * <p>Facebook is never email-linked: the Graph API exposes no trustworthy {@code email_verified} signal.
 */
@Service
public class SocialLoginService {

    static final String GOOGLE = "google";

    private final UserAccountRepository users;
    private final UserIdentityRepository identities;
    private final SocialAccountCreator creator;

    public SocialLoginService(UserAccountRepository users, UserIdentityRepository identities,
                              SocialAccountCreator creator) {
        this.users = users;
        this.identities = identities;
        this.creator = creator;
    }

    /**
     * @param provider  registration id ({@code google} | {@code facebook})
     * @param principal the authenticated OAuth2/OIDC user from the provider
     */
    public UserAccount resolveOrLink(String provider, OAuth2User principal) {
        SocialProfile profile = SocialProfile.from(provider, principal);

        // 1. Returning social login.
        var linked = identities.findByProviderAndProviderUserId(provider, profile.providerUserId());
        if (linked.isPresent()) {
            return loadUser(linked.get());
        }

        // 2. Verified-both-sides link (Google only, both provider and local account verified).
        if (profile.emailVerified() && profile.email() != null) {
            var match = users.findByEmail(profile.email());
            if (match.isPresent()) {
                if (match.get().isEmailVerified()) {
                    UserAccount user = match.get();
                    identities.save(UserIdentity.link(
                            user.getId(), provider, profile.providerUserId(), profile.email()));
                    return user;
                }
                // Matched an UNVERIFIED account (e.g. a squatted password account): do not merge. The
                // email is already owned, so the new separate account cannot hold it → account email null.
                return create(provider, profile, null);
            }
            // No existing account with this verified email → own it.
            return create(provider, profile, profile.email());
        }

        // 3. Never-link path (Facebook, or Google without a verified email). Keep the email on the new
        // account only if it is not already owned; otherwise leave it null (unique constraint).
        String accountEmail = (profile.email() != null && !users.existsByEmail(profile.email()))
                ? profile.email() : null;
        return create(provider, profile, accountEmail);
    }

    /** Create a fresh account, isolated so a concurrent create of the same identity doesn't poison this call. */
    private UserAccount create(String provider, SocialProfile profile, String accountEmail) {
        try {
            return creator.create(provider, profile.providerUserId(), accountEmail,
                    profile.email(), profile.displayName(), profile.emailVerified());
        } catch (DataIntegrityViolationException race) {
            // Someone created the same identity concurrently → re-resolve to the winner.
            UserIdentity winner = identities
                    .findByProviderAndProviderUserId(provider, profile.providerUserId())
                    .orElseThrow(() -> race);
            return loadUser(winner);
        }
    }

    private UserAccount loadUser(UserIdentity identity) {
        return users.findById(identity.getUserId())
                .orElseThrow(() -> new InvalidLoginException("Linked account not found"));
    }

    /**
     * Provider attributes reduced to what linking needs. Google is OIDC (has a verifiable
     * {@code email_verified} claim); Facebook has none, so its email is always treated as unverified.
     */
    record SocialProfile(String providerUserId, String email, boolean emailVerified, String displayName) {

        static SocialProfile from(String provider, OAuth2User principal) {
            String displayName = principal.getAttribute("name");
            if (GOOGLE.equals(provider)) {
                String sub = principal.getAttribute("sub");
                String email = EmailNormalizer.normalize(principal.getAttribute("email"));
                boolean verified = Boolean.TRUE.equals(principal.getAttribute("email_verified"));
                return new SocialProfile(sub, email, verified, displayName);
            }
            // Facebook (and any non-OIDC provider): id is the subject; email never trusted as verified.
            String id = principal.getAttribute("id");
            String email = EmailNormalizer.normalize(principal.getAttribute("email"));
            return new SocialProfile(id, email, false, displayName);
        }
    }
}
