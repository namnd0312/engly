package com.engly.identity.application;

import com.engly.identity.config.EmailVerificationProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the exact safety net that makes SMTP failure non-fatal to register/resend: a
 * down/slow/misconfigured mail server must never propagate out of {@link ActivationEmailSender}.
 */
class ActivationEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailVerificationProperties props = new EmailVerificationProperties(
            true, Duration.ofHours(24), Duration.ofSeconds(60), "no-reply@engly.local", "/verify-email");
    private final ActivationEmailSender sender =
            new ActivationEmailSender(mailSender, props, "http://localhost:4200");

    @Test
    void send_buildsLinkAndDispatchesViaJavaMailSender() {
        sender.send("alice@example.com", "raw-token-123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();

        assertThat(message.getTo()).containsExactly("alice@example.com");
        assertThat(message.getFrom()).isEqualTo("no-reply@engly.local");
        assertThat(message.getText()).contains("http://localhost:4200/verify-email?token=raw-token-123");
    }

    @Test
    void send_swallowsJavaMailSenderFailure_neverPropagates() {
        doThrow(new RuntimeException("SMTP relay unreachable")).when(mailSender).send(any(SimpleMailMessage.class));

        // Must not throw — a down SMTP server must never fail the register/resend request.
        sender.send("bob@example.com", "raw-token-456");
    }
}
