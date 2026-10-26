package com.engly.identity.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Provides the HMAC-backed {@link JwtEncoder}/{@link JwtDecoder} the app uses to sign and verify its
 * OWN access tokens with HS256. The signing key is derived (SHA-256) from
 * {@code app.security.jwt.secret} when set; otherwise a random ephemeral key is generated at startup
 * (single-instance dev only — tokens become invalid on restart). Provide a fixed secret via
 * env/secret ({@code JWT_SECRET}) for prod or multi-instance so sessions survive restarts.
 */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, MfaProperties.class})
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);
    private static final String KEY_ID = "engly-auth";

    private final SecretKey secretKey;

    public JwtKeyConfig(AuthProperties props) {
        this.secretKey = buildSecretKey(props);
    }

    private SecretKey buildSecretKey(AuthProperties props) {
        if (hasText(props.secret())) {
            // Derive a fixed 256-bit HMAC key from the configured secret (accepts any length input).
            return new SecretKeySpec(sha256(props.secret()), "HmacSHA256");
        }
        log.warn("No app.security.jwt.secret configured — generating an EPHEMERAL HMAC key. "
                + "Tokens will be invalidated on restart; set app.security.jwt.secret (env JWT_SECRET) "
                + "for production or multi-instance deployments.");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return new SecretKeySpec(random, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(secretKey.getEncoded())
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.HS256)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable for JWT secret derivation", e);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
