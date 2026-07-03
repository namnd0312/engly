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
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    /** Mint access JWT + new refresh token and write both cookies. Used by register/login/SSO. */
    @Transactional
    public void issueSession(UserAccount user, HttpServletResponse response) {
        Instant now = Instant.now();
        String accessToken = mintAccessToken(user, now);
        String rawRefresh = createRefreshToken(user, now);
        writeAccessCookie(response, accessToken);
        writeRefreshCookie(response, rawRefresh);
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
        RefreshToken token = refreshTokens.findByTokenHash(sha256(raw))
                .orElseThrow(() -> new InvalidLoginException("Invalid refresh token"));
        Instant now = Instant.now();
        if (!token.isActive(now)) {
            throw new InvalidLoginException("Expired refresh token");
        }
        token.revoke();
        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(() -> new InvalidLoginException("Unknown user"));
        writeAccessCookie(response, mintAccessToken(user, now));
        writeRefreshCookie(response, createRefreshToken(user, now));
    }

    /** Revoke the presented refresh token (best-effort) and clear both cookies. Used by logout. */
    @Transactional
    public void clearSession(HttpServletRequest request, HttpServletResponse response) {
        String raw = readCookie(request, props.cookie().refreshName());
        if (raw != null) {
            refreshTokens.findByTokenHash(sha256(raw)).ifPresent(RefreshToken::revoke);
        }
        expireCookie(response, props.cookie().accessName(), "/");
        expireCookie(response, props.cookie().refreshName(), props.cookie().refreshPath());
    }

    private String mintAccessToken(UserAccount user, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .subject(user.getSub())
                .claim("email", user.getEmail())
                .claim("name", user.getDisplayName())
                .claim("roles", List.copyOf(user.getRoles()))
                .build();
        JwsHeader header = JwsHeader.with(() -> "RS256").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String createRefreshToken(UserAccount user, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), sha256(raw), now.plus(props.refreshTokenTtl())));
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

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
