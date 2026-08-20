package com.engly.identity.application;

import com.engly.identity.config.EmailVerificationProperties;
import com.engly.identity.domain.EmailVerificationToken;
import com.engly.identity.domain.InvalidVerificationTokenException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.EmailVerificationTokenRepository;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Owns the email-activation-on-registration flow: issue a token + send the link, verify a raw
 * token, and resend with cooldown. See {@code EmailVerificationProperties#enabled} for the
 * rollback kill-switch (caller decides what to do when disabled — see {@code AuthController}).
 */
@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokens;
    private final UserAccountRepository users;
    private final ActivationEmailSender sender;
    private final EmailVerificationProperties props;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationTokenRepository tokens, UserAccountRepository users,
                                    ActivationEmailSender sender, EmailVerificationProperties props) {
        this.tokens = tokens;
        this.users = users;
        this.sender = sender;
        this.props = props;
    }

    /**
     * Invalidate any prior tokens, mint a fresh one, and dispatch the activation email after this
     * transaction commits (never block a DB connection on SMTP; never email a link for a row that
     * then rolls back). No-op if the user has no email (password accounts always do).
     */
    @Transactional
    public void startVerification(UserAccount user) {
        if (user.getEmail() == null) {
            return;
        }
        tokens.deleteByUserId(user.getId());

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(props.tokenTtl());
        tokens.save(new EmailVerificationToken(user.getId(), TokenHashing.sha256(rawToken), expiresAt));

        String email = user.getEmail();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sender.send(email, rawToken);
            }
        });
    }

    /** Rollback kill-switch state — see {@code AuthController.register}. */
    public boolean isEnabled() {
        return props.enabled();
    }

    /**
     * Used only when the gate is disabled: activate the account immediately, bypassing the token
     * flow. Re-fetches by id and mutates the managed instance (dirty-checking flushes it) rather
     * than {@code save()}-ing the caller's entity directly — that entity crossed a transaction
     * boundary (returned from {@code UserAccountService.register}'s already-committed transaction),
     * so merging it back would overwrite DB-managed columns (e.g. {@code updated_at}) with the
     * stale/never-fetched values it held at that point.
     */
    @Transactional
    public void markVerifiedImmediately(UserAccount user) {
        users.findById(user.getId()).ifPresent(UserAccount::markEmailVerified);
    }

    /** Atomically consumes the token and activates the account. Throws on invalid/expired/used. */
    @Transactional
    public UserAccount verify(String rawToken) {
        Instant now = Instant.now();
        int consumed = tokens.consumeIfUsable(TokenHashing.sha256(rawToken), now);
        if (consumed == 0) {
            throw new InvalidVerificationTokenException();
        }
        EmailVerificationToken token = tokens.findByTokenHash(TokenHashing.sha256(rawToken))
                .orElseThrow(InvalidVerificationTokenException::new);
        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(InvalidVerificationTokenException::new);
        user.markEmailVerified();
        return user;
    }

    /**
     * Generic: silently no-ops for an unknown/already-verified email or one still in cooldown — never
     * reveals account existence. Otherwise issues a fresh token (invalidating the prior one).
     */
    @Transactional
    public void resend(String email) {
        Optional<UserAccount> match = users.findByEmail(EmailNormalizer.normalize(email));
        if (match.isEmpty() || match.get().isEmailVerified()) {
            return;
        }
        UserAccount user = match.get();
        Optional<EmailVerificationToken> lastToken = tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
        if (lastToken.isPresent()) {
            Instant cooldownEnds = lastToken.get().getCreatedAt().plus(props.resendCooldown());
            if (Instant.now().isBefore(cooldownEnds)) {
                return;
            }
        }
        startVerification(user);
    }
}
