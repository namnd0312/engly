package com.engly.identity.application;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The success handler bridges a completed handshake into the app's own cookie session. Also asserts the
 * email-less (Facebook) path mints a valid session + serves /api/me, and that with no SSO env configured
 * no {@code ClientRegistrationRepository} bean exists (the app boots password-only).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class SsoSuccessHandlerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private SsoAuthenticationSuccessHandler handler;
    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;

    private static OAuth2AuthenticationToken token(String provider, OAuth2User principal) {
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), provider);
    }

    private static OAuth2User googleUser(String sub, String email) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", sub);
        attrs.put("email", email);
        attrs.put("email_verified", true);
        attrs.put("name", "G User");
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, "sub");
    }

    private static OAuth2User facebookUserNoEmail(String id) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", id);
        attrs.put("name", "FB User");
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, "id");
    }

    @Test
    void googleSuccess_setsSessionCookies_andRedirectsToFrontend() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                token("google", googleUser("g-" + UUID.randomUUID(), "handler@example.com")));

        assertThat(response.getCookie("ACCESS_TOKEN")).isNotNull();
        assertThat(response.getCookie("REFRESH_TOKEN")).isNotNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:4200/");
    }

    @Test
    void facebookNoEmail_mintsSession_andMeReturnsNullEmail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                token("facebook", facebookUserNoEmail("fb-" + UUID.randomUUID())));

        Cookie access = response.getCookie("ACCESS_TOKEN");
        assertThat(access).as("null-email account still mints an access token").isNotNull();

        mockMvc.perform(get("/api/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void noSsoEnv_noClientRegistrationRepositoryBean() {
        assertThat(context.getBeanNamesForType(ClientRegistrationRepository.class)).isEmpty();
    }
}
