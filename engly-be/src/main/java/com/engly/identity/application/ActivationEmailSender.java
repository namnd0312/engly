package com.engly.identity.application;

import com.engly.identity.config.EmailVerificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Sends the account-activation email. Plain text ({@link SimpleMailMessage}) — no HTML templating
 * dependency (YAGNI); can upgrade to MIME/HTML later without changing the caller's contract.
 *
 * <p>Send failures are swallowed (logged at WARN, never rethrown): a down/slow SMTP server must
 * never fail the register/resend request that triggered it. The account already exists and the
 * user can fall back to "resend" if the email never arrives.
 */
@Component
public class ActivationEmailSender {

    private static final Logger log = LoggerFactory.getLogger(ActivationEmailSender.class);

    private final JavaMailSender mailSender;
    private final EmailVerificationProperties props;
    private final String frontendUrl;

    public ActivationEmailSender(JavaMailSender mailSender, EmailVerificationProperties props,
                                 @Value("${app.frontend-url:http://localhost:4200}") String frontendUrl) {
        this.mailSender = mailSender;
        this.props = props;
        this.frontendUrl = frontendUrl;
    }

    public void send(String toEmail, String rawToken) {
        String link = buildLink(rawToken);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setFrom(props.fromAddress());
            message.setSubject("Kích hoạt tài khoản Engly");
            message.setText("Chào bạn,\n\n"
                    + "Vui lòng nhấn vào liên kết dưới đây để kích hoạt tài khoản Engly của bạn:\n"
                    + link + "\n\n"
                    + "Liên kết có hiệu lực trong 24 giờ. Nếu bạn không yêu cầu việc này, hãy bỏ qua email này.\n\n"
                    + "— Đội ngũ Engly");
            mailSender.send(message);
            log.info("Activation email dispatched to {} (link: {})", toEmail, link);
        } catch (Exception e) {
            log.warn("Failed to send activation email to {} (link: {}): {}", toEmail, link, e.getMessage());
        }
    }

    private String buildLink(String rawToken) {
        String encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        return frontendUrl + props.verifyPath() + "?token=" + encoded;
    }
}
