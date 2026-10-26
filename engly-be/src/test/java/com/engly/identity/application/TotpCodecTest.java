package com.engly.identity.application;

import com.engly.identity.config.MfaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpCodecTest {

    /** RFC 6238 Appendix B shared secret for the SHA-1 vectors. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private final TotpCodec codec = new TotpCodec(new MfaProperties(null, null, null, null, null));

    @ParameterizedTest(name = "T={0} → {1}")
    @CsvSource({
            "59,           94287082",
            "1111111109,   07081804",
            "1111111111,   14050471",
            "1234567890,   89005924",
            "2000000000,   69279037",
            "20000000000,  65353130",
    })
    void generate_matchesRfc6238Sha1Vectors(long epochSeconds, String expected) {
        long step = codec.currentStep(Instant.ofEpochSecond(epochSeconds));
        assertThat(codec.generate(RFC_SECRET, step, 8)).isEqualTo(expected);
    }

    @Test
    void sixDigitCode_isLastSixDigitsOfEightDigitCode() {
        long step = codec.currentStep(Instant.ofEpochSecond(1111111109L));
        assertThat(codec.generate(RFC_SECRET, step)).isEqualTo("081804");
    }

    @Test
    void verify_acceptsCurrentAndAdjacentSteps_rejectsBeyondWindow() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long current = codec.currentStep(now);

        assertThat(codec.verify(RFC_SECRET, codec.generate(RFC_SECRET, current), now)).hasValue(current);
        assertThat(codec.verify(RFC_SECRET, codec.generate(RFC_SECRET, current - 1), now)).hasValue(current - 1);
        assertThat(codec.verify(RFC_SECRET, codec.generate(RFC_SECRET, current + 1), now)).hasValue(current + 1);
        assertThat(codec.verify(RFC_SECRET, codec.generate(RFC_SECRET, current - 2), now)).isEmpty();
        assertThat(codec.verify(RFC_SECRET, codec.generate(RFC_SECRET, current + 2), now)).isEmpty();
    }

    @Test
    void verify_rejectsMalformedInput() {
        Instant now = Instant.now();
        assertThat(codec.verify(RFC_SECRET, "12345", now)).isEqualTo(OptionalLong.empty());
        assertThat(codec.verify(RFC_SECRET, "1234567", now)).isEmpty();
        assertThat(codec.verify(RFC_SECRET, "abcdef", now)).isEmpty();
        assertThat(codec.verify(RFC_SECRET, null, now)).isEmpty();
    }

    @Test
    void toBase32_matchesRfc4648() {
        assertThat(codec.toBase32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(codec.toBase32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(codec.toBase32(new byte[0])).isEmpty();
    }

    @Test
    void fromBase32_invertsToBase32_andToleratesPaddingAndCase() {
        byte[] secret = codec.newSecret();
        assertThat(codec.fromBase32(codec.toBase32(secret))).isEqualTo(secret);
        assertThat(codec.fromBase32("mzxw6ytboi======")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> codec.fromBase32("MZXW6YTB01")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otpauthUri_encodesIssuerAndLabel() {
        byte[] secret = "foobar".getBytes(StandardCharsets.US_ASCII);
        String uri = codec.otpauthUri("admin@example.com", secret);
        assertThat(uri).startsWith("otpauth://totp/Engly:admin%40example.com?secret=MZXW6YTBOI")
                .contains("&issuer=Engly")
                .contains("&algorithm=SHA1")
                .contains("&digits=6")
                .contains("&period=30");
    }

    @Test
    void newSecret_is160BitsAndRandom() {
        byte[] a = codec.newSecret();
        byte[] b = codec.newSecret();
        assertThat(a).hasSize(20);
        assertThat(a).isNotEqualTo(b);
    }
}
