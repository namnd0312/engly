package com.engly.identity.application;

import com.engly.identity.config.MfaProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Self-contained RFC 6238 TOTP (HMAC-SHA1, 30s step) plus the RFC 4648 Base32 encoding the
 * authenticator apps expect. Stateless: replay protection and lockout live on the caller side
 * ({@code MfaService}), which is why {@link #verify} returns the matched time-step rather than a boolean.
 */
@Component
public class TotpCodec {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private final MfaProperties props;
    private final SecureRandom random = new SecureRandom();

    public TotpCodec(MfaProperties props) {
        this.props = props;
    }

    /** Fresh 160-bit shared secret (the size RFC 4226 recommends for SHA-1). */
    public byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    /** Current 30-second time step for {@code now}. */
    public long currentStep(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), props.periodSeconds());
    }

    /** The code for a given step with the configured digit count. */
    public String generate(byte[] secret, long step) {
        return generate(secret, step, props.digits());
    }

    /** RFC 4226 dynamic truncation of HMAC-SHA1(secret, step) to {@code digits} decimal digits. */
    public String generate(byte[] secret, long step, int digits) {
        byte[] counter = ByteBuffer.allocate(Long.BYTES).putLong(step).array();
        byte[] hash = hmacSha1(secret, counter);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", otp);
    }

    /**
     * Check {@code code} against the steps {@code now ± window}. Returns the matching step so the
     * caller can refuse a second use of the same code; empty when nothing matches. Comparison is
     * constant-time per candidate; the digit-count precheck is on public format only.
     */
    public OptionalLong verify(byte[] secret, String code, Instant now) {
        if (code == null || code.length() != props.digits() || !code.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        byte[] submitted = code.getBytes(StandardCharsets.US_ASCII);
        long current = currentStep(now);
        for (long step = current - props.window(); step <= current + props.window(); step++) {
            byte[] expected = generate(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, submitted)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** RFC 4648 Base32, uppercase, no padding — the form authenticator apps accept for manual entry. */
    public String toBase32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    /** Inverse of {@link #toBase32}: accepts upper/lower case, ignores '=' padding and spaces. */
    public byte[] fromBase32(String text) {
        String clean = text.replace(" ", "").replace("=", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    /** otpauth:// provisioning URI encoded in the enrollment QR code (Key Uri Format). */
    public String otpauthUri(String accountLabel, byte[] secret) {
        String issuer = urlEncode(props.issuer());
        String label = urlEncode(accountLabel);
        return "otpauth://totp/" + issuer + ":" + label
                + "?secret=" + toBase32(secret)
                + "&issuer=" + issuer
                + "&algorithm=SHA1"
                + "&digits=" + props.digits()
                + "&period=" + props.periodSeconds();
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable for TOTP", e);
        }
    }

    private static String urlEncode(String s) {
        // Key Uri Format expects %20 rather than '+' for spaces.
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
