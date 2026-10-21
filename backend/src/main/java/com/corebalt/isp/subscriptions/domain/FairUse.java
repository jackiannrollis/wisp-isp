package com.corebalt.isp.subscriptions.domain;

/**
 * Whether a line's cycle usage has reached its package's limits (docs/09 step 8b). The same comparison
 * is made in SQL by {@code v_radius_auth_v6} and {@code subscription_line_access()}:
 * {@code octets >= limit}, a null limit meaning unlimited (docs/03). Usage is download plus upload in
 * the line's current cycle, from its last renewal (Q12 default); it is zero when the cycle start is
 * unknown or the counter still holds an older cycle.
 */
public final class FairUse {

    private FairUse() {}

    public static Limits limits(final Long fairUseBytes, final Long dataCapBytes, final long cycleOctets) {
        return new Limits(
                fairUseBytes != null && cycleOctets >= fairUseBytes,
                dataCapBytes != null && cycleOctets >= dataCapBytes);
    }

    public record Limits(boolean fairUseExceeded, boolean dataCapReached) {

        public static final Limits NONE = new Limits(false, false);
    }
}
