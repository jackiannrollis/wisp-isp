package com.corebalt.isp.network.domain;

/**
 * Mikrotik-Rate-Limit as {@code v_radius_auth_v4} writes it (N4): upload/download (router rx/tx) in
 * kbit/s, written as M when divisible by 1000 (5000/10000 becomes 5M/10M), otherwise k. A CoA must
 * set exactly what the next login would get; AccessChangesIT compares the two.
 */
public final class RateLimits {

    private RateLimits() {}

    public static String of(final int uploadKbps, final int downloadKbps) {
        return unit(uploadKbps) + "/" + unit(downloadKbps);
    }

    private static String unit(final int kbps) {
        if (kbps <= 0) {
            throw new IllegalArgumentException("N4: a speed is a positive number of kbit/s, got " + kbps);
        }
        return kbps % 1000 == 0 ? (kbps / 1000) + "M" : kbps + "k";
    }
}
