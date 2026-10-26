package com.engly.identity.application;

import com.engly.identity.config.AuthProperties;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.RefreshToken;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.RefreshTokenRepository;
import com.engly.identity.infrastructure.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Issues and rotates the app's own session: a short-lived access JWT (signed by {@link JwtEncoder})
 * plus an opaque, rotating refresh token — both delivered as httpOnly cookies. Only the SHA-256 hash
 * of each refresh token is persisted; the raw value lives only in the browser cookie.
 */
@Service
public class AuthTokenService {

    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRepository refreshTokens;
    private final UserAccountRepository users;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public AuthTokenService(JwtEncoder jwtEncoder, RefreshTokenRepository refreshTokens,
                            UserAccountRepository users, AuthProperties props) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.props = props;
    }

    /** Cookie name the {@code BearerTokenResolver} reads the access JWT from. */
    public String accessCookieName() {
        return props.cookie().accessName();
    }

    /**
     * Mint access JWT + new refresh token and write both cookies. Used by register/login/SSO. A fresh
     * session never carries the TOTP step-up ({@code mfa=false}); see {@link #upgradeToMfa}.
     */
    @Transactional
    public void issueSession(UserAccount user, HttpServletResponse response) {
        Instant now = Instant.now();
        String accessToken = mintAccessToken(user, now, false);
        String rawRefresh = createRefreshToken(user, now, false);
        writeAccessCookie(response, accessToken);
        writeRefreshCookie(response, rawRefresh);
    }

    /**
     * Record a passed TOTP check on the current session: flag the presented refresh token so rotation
     * keeps the step-up, and re-mint the access cookie with {@code mfa=true}. The refresh cookie
     * itself is left as is. 401 when the refresh cookie is missing, inactive, or belongs to another user.
     */
    @Transactional
    public void upgradeToMfa(HttpServletRequest request, HttpServletResponse response, UserAccount user) {
        String raw = readCookie(request, props.cookie().refreshName());
        if (raw == null) {
            throw new InvalidLoginException("Missing refresh token");
        }
        Instant now = Instant.now();
        if (refreshTokens.markMfaVerifiedIfActive(TokenHashing.sha256(raw), user.getId(), now) != 1) {
            throw new InvalidLoginException("Invalid refresh token");
        }
        writeAccessCookie(response, mintAccessToken(user, now, true));
    }

    /**
     * Validate the refresh cookie, rotate it (revoke old + issue new), and write fresh cookies.
     * Throws {@link InvalidLoginException} (→ 401) when the cookie is missing, unknown, revoked,
     * or expired.
     */
    @Transactional
    public void rotate(HttpServletRequest request, HttpServletResponse response) {
        String raw = readCookie(request, props.cookie().refreshName());
        if (raw == null) {
            throw new InvalidLoginException("Missing refresh token");
        }
        RefreshToken token = refreshTokens.findByTokenHash(TokenHashing.sha256(raw))
                .orElseThrow(() -> new InvalidLoginException("Invalid refresh token"));
        Instant now = Instant.now();
        if (!token.isActive(now)) {
            throw new InvalidLoginException("Expired refresh token");
        }
        token.revoke();
        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(() -> new InvalidLoginException("Unknown user"));
        // The step-up travels with the refresh chain: an admin who entered a code is not asked again
        // every 15 minutes, and disabling 2FA clears the flag so the next rotation drops it.
        boolean mfa = token.isMfaVerified();
        writeAccessCookie(response, mintAccessToken(user, now, mfa));
        writeRefreshCookie(response, createRefreshToken(user, now, mfa));
    }

    /** Revoke the presented refresh token (best-effort) and clear both cookies. Used by logout. */
    @Transactional
    public void clearSession(HttpServletRequest request, HttpServletResponse response) {
        String raw = readCookie(request, props.cookie().refreshName());
        if (raw != null) {
            refreshTokens.findByTokenHash(TokenHashing.sha256(raw)).ifPresent(RefreshToken::revoke);
        }
        expireCookie(response, props.cookie().accessName(), "/");
        expireCookie(response, props.cookie().refreshName(), props.cookie().refreshPath());
    }

    private String mintAccessToken(UserAccount user, Instant now, boolean mfaVerified) {
        // email/name are optional: social accounts (e.g. Facebook without email) may have a null email,
        // and JwtClaimsSet rejects null claim values — so only add them when present.
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .subject(user.getSub())
                .claim("roles", List.copyOf(user.getRoles()))
                .claim(MfaClaims.CLAIM, mfaVerified);
        if (user.getEmail() != null) claims.claim("email", user.getEmail());
        if (user.getDisplayName() != null) claims.claim("name", user.getDisplayName());
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private String createRefreshToken(UserAccount user, Instant now, boolean mfaVerified) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), TokenHashing.sha256(raw),
                now.plus(props.refreshTokenTtl()), mfaVerified));
        return raw;
    }

    private void writeAccessCookie(HttpServletResponse response, String value) {
        addCookie(response, props.cookie().accessName(), value, "/", props.accessTokenTtl().getSeconds());
    }

    private void writeRefreshCookie(HttpServletResponse response, String value) {
        addCookie(response, props.cookie().refreshName(), value,
                props.cookie().refreshPath(), props.refreshTokenTtl().getSeconds());
    }

    private void addCookie(HttpServletResponse response, String name, String value, String path, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.cookie().secure())
                .path(path)
                .maxAge(maxAgeSeconds)
                .sameSite(props.cookie().sameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void expireCookie(HttpServletResponse response, String name, String path) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(props.cookie().secure())
                .path(path)
                .maxAge(0)
                .sameSite(props.cookie().sameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
