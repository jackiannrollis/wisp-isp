package com.corebalt.isp.network.domain;

import java.security.SecureRandom;

/**
 * Platform-generated RADIUS credentials (ADR 0009). Letters and digits only, without look-alikes
 * ({@code 0 O 1 l I}), so a password can be read out to a customer and a shared secret can be pasted
 * into a RouterOS script without quoting.
 */
public final class SecretGenerator {

    /** PPPoE passwords: 16 characters, about 92 bits. */
    public static final int PASSWORD_LENGTH = 16;

    /** Router RADIUS shared secrets: 32 characters, about 184 bits. */
    public static final int SHARED_SECRET_LENGTH = 32;

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretGenerator() {}

    public static String pppoePassword() {
        return generate(PASSWORD_LENGTH);
    }

    public static String sharedSecret() {
        return generate(SHARED_SECRET_LENGTH);
    }

    static String generate(final int length) {
        final StringBuilder secret = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            secret.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return secret.toString();
    }
}
