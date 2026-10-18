package com.corebalt.isp.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** UUIDv7 (RFC 9562) generator: 48-bit Unix milliseconds, then random bits; time-ordered keys. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID next() {
        return next(Clock.systemUTC());
    }

    public static UUID next(final Clock clock) {
        final long millis = clock.millis();
        final long randA = RANDOM.nextInt(1 << 12);
        final long msb = (millis << 16) | (0x7L << 12) | randA;
        final long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
