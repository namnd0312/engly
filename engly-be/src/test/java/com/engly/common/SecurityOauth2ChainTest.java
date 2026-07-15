package com.engly.common;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With a provider configured, the OAuth2 login chain (Chain A, {@code securityMatcher("/oauth2/**",
 * "/login/oauth2/**")}) is active and the authorization endpoint redirects to the provider — while the
 * stateless {@code /api/**} chain (Chain B) is untouched: an unauthenticated API call still 401s. This
 * proves Chain A's securityMatcher did not swallow /api/** (red-team Finding #8).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class SecurityOauth2ChainTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Enable the Google provider so the OAuth2 login chain is created for this context.
        registry.add("GOOGLE_CLIENT_ID", () -> "test-google-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-google-client-secret");
    }

    @Autowired private MockMvc mockMvc;

    @Test
    void authorizationEndpoint_redirectsToProvider() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com")));
    }

    @Test
    void apiChainStillStateless_unauthenticatedApiReturns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }
}
