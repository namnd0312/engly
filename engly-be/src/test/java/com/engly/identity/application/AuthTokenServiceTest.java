package com.engly.identity.application;

import com.engly.identity.config.AuthProperties;
import com.engly.identity.config.JwtKeyConfig;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.RefreshToken;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.RefreshTokenRepository;
import com.engly.identity.infrastructure.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthTokenService}: access-JWT minting, refresh-token rotation, and cookie
 * issuing/clearing. Uses a real RSA encoder/decoder (ephemeral key from {@link JwtKeyConfig}) and
 * mocked repositories.
 */
class AuthTokenServiceTest {

    private RefreshTokenRepository refreshTokens;
    private UserAccountRepository users;
    private AuthTokenService service;
    private JwtDecoder decoder;

    private final AuthProperties props = new AuthProperties(
            "engly", Duration.ofMinutes(15), Duration.ofDays(30), null, null, null);

    @BeforeEach
    void setUp() {
        JwtKeyConfig keyConfig = new JwtKeyConfig(props);
        decoder = keyConfig.jwtDecoder();
        refreshTokens = mock(RefreshTokenRepository.class);
        users = mock(UserAccountRepository.class);
        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuthTokenService(keyConfig.jwtEncoder(), refreshTokens, users, props);
    }

    private UserAccount sampleUser() {
        return UserAccount.localUser("local-7", "u@example.com", "irrelevant-hash", "Sample User");
    }

    @Test
    void issueSession_writesBothCookies_andAccessTokenCarriesClaims() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.issueSession(sampleUser(), response);

        Cookie access = response.getCookie("ACCESS_TOKEN");
        Cookie refresh = response.getCookie("REFRESH_TOKEN");
        assertThat(access).isNotNull();
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/auth");

        Jwt decoded = decoder.decode(access.getValue());
        assertThat(decoded.getSubject()).isEqualTo("local-7");
        assertThat(decoded.getClaimAsString("email")).isEqualTo("u@example.com");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("ROLE_USER");

        verify(refreshTokens).save(any(RefreshToken.class));
    }

    @Test
    void rotate_validRefreshCookie_revokesOldAndIssuesNew() {
        UserAccount user = sampleUser();

        // First issue a session to capture a real refresh cookie + the persisted token.
        MockHttpServletResponse first = new MockHttpServletResponse();
        service.issueSession(user, first);
        String rawRefresh = first.getCookie("REFRESH_TOKEN").getValue();

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        RefreshToken stored = saved.getValue();

        when(refreshTokens.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));
        // user.getId() is null without JPA → match any lookup (incl. null) via any().
        when(users.findById(any())).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("REFRESH_TOKEN", rawRefresh));
        MockHttpServletResponse second = new MockHttpServletResponse();

        service.rotate(request, second);

        assertThat(stored.isRevoked()).isTrue();
        assertThat(second.getCookie("ACCESS_TOKEN")).isNotNull();
        assertThat(second.getCookie("REFRESH_TOKEN")).isNotNull();
        // A second refresh row was persisted (rotation).
        verify(refreshTokens, org.mockito.Mockito.times(2)).save(any(RefreshToken.class));
    }

    @Test
    void rotate_missingCookie_throws401() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThatThrownBy(() -> service.rotate(request, new MockHttpServletResponse()))
                .isInstanceOf(InvalidLoginException.class);
    }

    @Test
    void rotate_unknownToken_throws401() {
        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("REFRESH_TOKEN", "some-unknown-token"));

        assertThatThrownBy(() -> service.rotate(request, new MockHttpServletResponse()))
                .isInstanceOf(InvalidLoginException.class);
    }

    @Test
    void rotate_expiredToken_throws401() {
        RefreshToken expired = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().minusSeconds(60));
        when(refreshTokens.findByTokenHash("hash")).thenReturn(Optional.of(expired));
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Token value irrelevant — service hashes it; stub matches the resulting lookup via any() below.
        request.setCookies(new Cookie("REFRESH_TOKEN", "raw"));
        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate(request, new MockHttpServletResponse()))
                .isInstanceOf(InvalidLoginException.class);
    }

    @Test
    void clearSession_revokesPresentedTokenAndExpiresCookies() {
        RefreshToken active = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().plusSeconds(3600));
        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.of(active));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("REFRESH_TOKEN", "raw"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clearSession(request, response);

        assertThat(active.isRevoked()).isTrue();
        assertThat(response.getCookie("ACCESS_TOKEN").getMaxAge()).isZero();
        assertThat(response.getCookie("REFRESH_TOKEN").getMaxAge()).isZero();
    }

    @Test
    void accessCookieName_returnsConfiguredName() {
        assertThat(service.accessCookieName()).isEqualTo("ACCESS_TOKEN");
    }
}
