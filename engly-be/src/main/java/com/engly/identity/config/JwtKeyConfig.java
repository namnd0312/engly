package com.engly.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Provides the RSA-backed {@link JwtEncoder}/{@link JwtDecoder} the app uses to sign and verify its
 * OWN access tokens. Keys come from {@code app.security.jwt.private-key/public-key} (PEM) when set;
 * otherwise an ephemeral RSA-2048 keypair is generated at startup (single-instance dev only —
 * tokens become invalid on restart). Provide real keys via env/secret for prod or multi-instance.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);
    private static final String KEY_ID = "engly-auth";

    private final RSAKey rsaKey;

    public JwtKeyConfig(AuthProperties props) {
        this.rsaKey = buildRsaKey(props);
    }

    private RSAKey buildRsaKey(AuthProperties props) {
        if (hasText(props.privateKey()) && hasText(props.publicKey())) {
            try {
                RSAPublicKey publicKey = parsePublicKey(props.publicKey());
                RSAPrivateKey privateKey = parsePrivateKey(props.privateKey());
                return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(KEY_ID).build();
            } catch (Exception e) {
                throw new IllegalStateException("Invalid app.security.jwt RSA key material", e);
            }
        }
        log.warn("No app.security.jwt RSA keys configured — generating an EPHEMERAL keypair. "
                + "Tokens will be invalidated on restart; set app.security.jwt.private-key/public-key "
                + "(env/secret) for production or multi-instance deployments.");
        KeyPair pair = generateRsaKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(KEY_ID)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build JwtDecoder from RSA key", e);
        }
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA keypair", e);
        }
    }

    private static RSAPublicKey parsePublicKey(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(stripPem(pem));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static RSAPrivateKey parsePrivateKey(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(stripPem(pem));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Strip PEM armor + whitespace, leaving the base64 body (accepts raw base64 too). */
    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
