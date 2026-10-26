package com.engly.identity.application;

import com.engly.identity.config.MfaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts TOTP secrets at rest with AES-256-GCM so a read-only DB leak does not hand out working
 * second factors. Stored form is {@code base64url(iv || ciphertext || tag)} with a fresh 96-bit IV
 * per encryption. The key is SHA-256-derived from {@code app.security.mfa.secret-key}, mirroring how
 * {@code JwtKeyConfig} derives the JWT key; absent → ephemeral key + WARN (every restart invalidates
 * all enrollments).
 */
@Component
public class MfaSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(MfaSecretCipher.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(MfaProperties props) {
        this.key = buildKey(props);
    }

    public String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt TOTP secret", e);
        }
    }

    public byte[] decrypt(String stored) {
        try {
            byte[] in = Base64.getUrlDecoder().decode(stored);
            if (in.length <= IV_BYTES) {
                throw new IllegalStateException("Stored TOTP secret is too short");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return cipher.doFinal(Arrays.copyOfRange(in, IV_BYTES, in.length));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt TOTP secret — MFA_SECRET_KEY changed?", e);
        }
    }

    private static SecretKey buildKey(MfaProperties props) {
        if (props.secretKey() != null && !props.secretKey().isBlank()) {
            return new SecretKeySpec(sha256(props.secretKey()), "AES");
        }
        log.warn("No app.security.mfa.secret-key configured — generating an EPHEMERAL key. "
                + "After a restart enrolled admins are locked out until a DB reset; set app.security.mfa.secret-key "
                + "(env MFA_SECRET_KEY) for any persistent deployment.");
        byte[] randomKey = new byte[32];
        new SecureRandom().nextBytes(randomKey);
        return new SecretKeySpec(randomKey, "AES");
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable for MFA key derivation", e);
        }
    }
}
