package com.corebalt.isp.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CredentialsTest {

    /** RFC 6238 appendix B, SHA-1 secret "12345678901234567890" (base32 below), 8 digits truncated to 6. */
    static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void totpMatchesTheRfc6238Vectors() {
        assertThat(Credentials.totp(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Credentials.totp(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Credentials.totp(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
    }

    @Test
    void totpAcceptsOneStepOfDriftOnly() {
        final Instant now = Instant.ofEpochSecond(1_234_567_890L);
        assertThat(Credentials.verifyTotp(RFC_SECRET, Credentials.totp(RFC_SECRET, now.getEpochSecond() / 30 - 1), now)).isTrue();
        assertThat(Credentials.verifyTotp(RFC_SECRET, Credentials.totp(RFC_SECRET, now.getEpochSecond() / 30 + 2), now)).isFalse();
        assertThat(Credentials.verifyTotp(RFC_SECRET, "12345", now)).isFalse();
        assertThat(Credentials.verifyTotp(Credentials.newTotpSecret(), "000000", now)).isFalse();
    }

    @Test
    void passwordsAndPinsAreArgon2idAndPolicyChecked() {
        final String hash = Credentials.hash("correct horse battery");
        assertThat(hash).startsWith("$argon2id$");
        assertThat(Credentials.matches("correct horse battery", hash)).isTrue();
        assertThat(Credentials.matches("wrong", hash)).isFalse();
        assertThat(Credentials.acceptablePassword("short", "a@b.test")).isFalse();
        assertThat(Credentials.acceptablePassword("a-long-enough-pass", "a@b.test")).isTrue();
        assertThat(Credentials.acceptablePin("1234")).isFalse();
        assertThat(Credentials.acceptablePin("1111")).isFalse();
        assertThat(Credentials.acceptablePin("9876")).isFalse();
        assertThat(Credentials.acceptablePin("2580")).isTrue();
        assertThat(Credentials.acceptablePin("12a4")).isFalse();
    }

    @Test
    void otpauthUriNamesIssuerAndAccount() {
        assertThat(Credentials.otpauthUri("Corebalt CB", "ann@lab.test", "ABC"))
                .isEqualTo("otpauth://totp/Corebalt%20CB%3Aann%40lab.test?secret=ABC&issuer=Corebalt%20CB&digits=6&period=30");
    }
}
