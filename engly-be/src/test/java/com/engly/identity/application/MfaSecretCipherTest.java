package com.engly.identity.application;

import com.engly.identity.config.MfaProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {

    private static MfaSecretCipher withKey(String key) {
        return new MfaSecretCipher(new MfaProperties(key, null, null, null, null));
    }

    @Test
    void roundTrip_restoresPlaintext() {
        MfaSecretCipher cipher = withKey("test-key");
        byte[] plain = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    void encrypt_usesFreshIvEachTime() {
        MfaSecretCipher cipher = withKey("test-key");
        byte[] plain = new byte[20];
        assertThat(cipher.encrypt(plain)).isNotEqualTo(cipher.encrypt(plain));
    }

    @Test
    void decrypt_rejectsTamperedCiphertext() {
        MfaSecretCipher cipher = withKey("test-key");
        String stored = cipher.encrypt(new byte[20]);
        char last = stored.charAt(stored.length() - 1);
        String tampered = stored.substring(0, stored.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_withDifferentKeyFails() {
        String stored = withKey("key-one").encrypt(new byte[20]);
        assertThatThrownBy(() -> withKey("key-two").decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MFA_SECRET_KEY");
    }

    @Test
    void ephemeralKey_stillRoundTripsWithinInstance() {
        MfaSecretCipher cipher = withKey("");
        byte[] plain = new byte[]{1, 2, 3};
        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain);
    }
}
