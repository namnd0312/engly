package com.engly.identity;

import com.engly.identity.application.ActivationEmailSender;
import com.engly.identity.application.TotpCodec;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end admin step-up: a real admin session (register → activate → ROLE_ADMIN → login) walks
 * through enrollment, the coded 403s on /api/admin/**, verification, refresh rotation, lockout,
 * replay and disable. Codes are computed with the real {@link TotpCodec} from the Base32 secret the
 * enroll endpoint returns — exactly what an authenticator app does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class MfaStepUpFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserAccountRepository users;
    @Autowired private TotpCodec codec;
    @Autowired private ObjectMapper json;
    @MockitoBean private ActivationEmailSender emailSender;
    @Autowired private MutableClock clock;

    /** Settable clock replacing the production MFA clock so tests can step TOTP time forward. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.now();
        void advanceStep() { now = now.plusSeconds(30); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testMfaClock() {
            return new MutableClock();
        }
    }

    /** Cookies of a live session; access is re-read after verify/refresh. */
    private record Session(Cookie access, Cookie refresh) {}

    private static Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie " + name).isNotNull();
        return c;
    }

    /** Register + activate + (optionally) grant ROLE_ADMIN, then password-login → fresh session. */
    private Session loginAs(String email, boolean admin) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"supersecret\",\"displayName\":\"U\"}"))
                .andExpect(status().isAccepted());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(anyString(), token.capture());
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token.getValue() + "\"}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.reset(emailSender);

        if (admin) {
            UserAccount user = users.findByEmail(email).orElseThrow();
            user.getRoles().add("ROLE_ADMIN");
            users.save(user);
        }
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"supersecret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(cookie(login, "ACCESS_TOKEN"), cookie(login, "REFRESH_TOKEN"));
    }

    /** Enroll + confirm; returns the raw secret so the test can mint codes like an authenticator. */
    private byte[] enrollAndConfirm(Session s) throws Exception {
        MvcResult enrolled = mockMvc.perform(post("/api/profile/mfa/enroll").cookie(s.access()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/Engly:")))
                .andReturn();
        JsonNode body = json.readTree(enrolled.getResponse().getContentAsString());
        byte[] secret = codec.fromBase32(body.get("secret").asText());
        mockMvc.perform(post("/api/profile/mfa/confirm").cookie(s.access()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(secret)))
                .andExpect(status().isNoContent());
        return secret;
    }

    /** Code for the current step of the test clock. */
    private String codeBody(byte[] secret) {
        return "{\"code\":\"" + codec.generate(secret, codec.currentStep(clock.instant())) + "\"}";
    }

    @Test
    void adminWithoutEnrollment_adminApiReturns403EnrollmentRequired_andMeShowsFlags() throws Exception {
        Session s = loginAs("mfa-none@example.com", true);

        mockMvc.perform(get("/api/admin/decks").cookie(s.access()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
        mockMvc.perform(get("/api/me").cookie(s.access()))
                .andExpect(jsonPath("$.isAdmin").value(true))
                .andExpect(jsonPath("$.mfaEnrolled").value(false))
                .andExpect(jsonPath("$.mfaVerified").value(false));
    }

    @Test
    void encodedAdminPath_withoutMfa_isStillDenied() throws Exception {
        Session s = loginAs("mfa-encoded@example.com", true);

        // %61 = 'a'. The step-up must apply to the normalized path, exactly like the role check.
        mockMvc.perform(get(URI.create("/api/%61dmin/decks")).cookie(s.access()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
    }

    @Test
    void parallelWrongCodes_lockoutStillAllowsExactlyFiveGuesses() throws Exception {
        Session s = loginAs("mfa-parallel@example.com", true);
        enrollAndConfirm(s);

        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> mockMvc.perform(post("/api/auth/mfa/verify")
                                .cookie(s.access(), s.refresh()).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                        .andReturn().getResponse().getStatus()));
            }
            long evaluated = 0;
            long locked = 0;
            for (Future<Integer> f : results) {
                int status = f.get(60, TimeUnit.SECONDS);
                if (status == 400) evaluated++;
                if (status == 429) locked++;
            }
            assertThat(evaluated).as("wrong codes actually evaluated").isEqualTo(5);
            assertThat(locked).isEqualTo(attempts - 5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void enrollConfirm_thenAdminApiReturns403MfaRequired() throws Exception {
        Session s = loginAs("mfa-enrolled@example.com", true);
        enrollAndConfirm(s);

        mockMvc.perform(get("/api/admin/decks").cookie(s.access()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mockMvc.perform(get("/api/me").cookie(s.access()))
                .andExpect(jsonPath("$.mfaEnrolled").value(true))
                .andExpect(jsonPath("$.mfaVerified").value(false));
    }

    @Test
    void verify_thenAdminApiReturns200_andSurvivesRefresh() throws Exception {
        Session s = loginAs("mfa-verify@example.com", true);
        byte[] secret = enrollAndConfirm(s);

        // The confirmation already consumed this step's code → wait for the next step (≤30s).
        MvcResult verified = mockMvc.perform(post("/api/auth/mfa/verify")
                        .cookie(s.access(), s.refresh()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(nextStepCodeBody(secret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaVerified").value(true))
                .andReturn();
        Cookie upgraded = cookie(verified, "ACCESS_TOKEN");

        mockMvc.perform(get("/api/admin/decks").cookie(upgraded)).andExpect(status().isOk());
        mockMvc.perform(get("/api/me").cookie(upgraded)).andExpect(jsonPath("$.mfaVerified").value(true));

        // Refresh rotation keeps the step-up: the re-minted access cookie still opens /api/admin.
        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(s.refresh()))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(get("/api/admin/decks").cookie(cookie(refreshed, "ACCESS_TOKEN")))
                .andExpect(status().isOk());
    }

    @Test
    void verify_withoutSession_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/mfa/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fiveWrongCodes_lockOut_returns429() throws Exception {
        Session s = loginAs("mfa-lock@example.com", true);
        byte[] secret = enrollAndConfirm(s);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/mfa/verify").cookie(s.access(), s.refresh()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));
        }
        mockMvc.perform(post("/api/auth/mfa/verify").cookie(s.access(), s.refresh()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(nextStepCodeBody(secret)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MFA_LOCKED"));
    }

    @Test
    void sameCodeTwice_secondIsRejected() throws Exception {
        Session s = loginAs("mfa-replay@example.com", true);
        byte[] secret = enrollAndConfirm(s);
        String body = nextStepCodeBody(secret);

        mockMvc.perform(post("/api/auth/mfa/verify").cookie(s.access(), s.refresh()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/mfa/verify").cookie(s.access(), s.refresh()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));
    }

    @Test
    void disable_thenAdminApiReturns403Again_afterRefresh() throws Exception {
        Session s = loginAs("mfa-disable@example.com", true);
        byte[] secret = enrollAndConfirm(s);
        MvcResult verified = mockMvc.perform(post("/api/auth/mfa/verify").cookie(s.access(), s.refresh()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(nextStepCodeBody(secret)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie upgraded = cookie(verified, "ACCESS_TOKEN");

        mockMvc.perform(delete("/api/profile/mfa").cookie(upgraded).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(nextStepCodeBody(secret)))
                .andExpect(status().isNoContent());

        // The flag is dropped from the refresh chain → the next rotation mints mfa=false.
        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(s.refresh()))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(get("/api/admin/decks").cookie(cookie(refreshed, "ACCESS_TOKEN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
    }

    @Test
    void regularUser_meHasFlagsFalse_andStudyApiUnaffected() throws Exception {
        Session s = loginAs("mfa-regular@example.com", false);

        mockMvc.perform(get("/api/me").cookie(s.access()))
                .andExpect(jsonPath("$.isAdmin").value(false))
                .andExpect(jsonPath("$.mfaEnrolled").value(false))
                .andExpect(jsonPath("$.mfaVerified").value(false));
        mockMvc.perform(get("/api/study/decks").cookie(s.access())).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/decks").cookie(s.access())).andExpect(status().isForbidden());
    }

    /** Move the test clock one 30s step forward, then return a code for that new step (never a replay). */
    private String nextStepCodeBody(byte[] secret) {
        clock.advanceStep();
        return codeBody(secret);
    }
}
