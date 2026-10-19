package com.corebalt.isp.identity.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Credential primitives (ADR 0010): Argon2id for passwords and PINs, RFC 6238 TOTP (SHA-1, 30 s,
 * 6 digits, one step of clock drift either way), random one-time codes and opaque tokens, SHA-256
 * for values stored only to be compared (refresh tokens, OTPs, recovery codes).
 */
public final class Credentials {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Argon2PasswordEncoder ARGON2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String READABLE = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    public static final int MIN_PASSWORD = 12;

    private Credentials() {}

    public static String hash(final String secret) {
        return ARGON2.encode(secret);
    }

    public static boolean matches(final String secret, final String hash) {
        return secret != null && hash != null && ARGON2.matches(secret, hash);
    }

    /** A new password must be at least 12 characters and not the email or phone it belongs to. */
    public static boolean acceptablePassword(final String password, final String identity) {
        return password != null && password.length() >= MIN_PASSWORD && password.length() <= 200
                && !password.equalsIgnoreCase(identity);
    }

    /** A PIN is 4 to 6 digits and not one repeated digit or a straight run. */
    public static boolean acceptablePin(final String pin) {
        if (pin == null || !pin.matches("[0-9]{4,6}")) {
            return false;
        }
        boolean same = true;
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length(); i++) {
            same &= pin.charAt(i) == pin.charAt(0);
            ascending &= pin.charAt(i) - pin.charAt(i - 1) == 1;
            descending &= pin.charAt(i - 1) - pin.charAt(i) == 1;
        }
        return !same && !ascending && !descending;
    }

    /** Temporary passwords and recovery codes: letters and digits without look-alikes. */
    public static String readable(final int length) {
        final StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(READABLE.charAt(RANDOM.nextInt(READABLE.length())));
        }
        return out.toString();
    }

    public static String digits(final int length) {
        final StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(RANDOM.nextInt(10));
        }
        return out.toString();
    }

    /** An opaque bearer value (refresh tokens): 256 random bits, URL-safe. */
    public static String opaque() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new TOTP secret, base32 (160 bits), for authenticator apps. */
    public static String newTotpSecret() {
        final byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        final StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (final byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return out.toString();
    }

    public static String otpauthUri(final String issuer, final String account, final String secret) {
        final String label = java.net.URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer="
                + java.net.URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20") + "&digits=6&period=30";
    }

    public static boolean verifyTotp(final String base32Secret, final String code, final Instant now) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return false;
        }
        final long step = now.getEpochSecond() / 30;
        for (long drift = -1; drift <= 1; drift++) {
            if (MessageDigest.isEqual(totp(base32Secret, step + drift).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** The code for a 30-second step (tests and enrolment checks use it). */
    public static String totp(final String base32Secret, final long step) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(base32Secret), "HmacSHA1"));
            final byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            final int offset = hash[hash.length - 1] & 0xf;
            final int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decodeBase32(final String text) {
        final String clean = text.replace("=", "").replace(" ", "").toUpperCase();
        final ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (final char c : clean.toCharArray()) {
            final int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("not base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return out.array();
    }
}
