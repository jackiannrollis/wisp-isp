package com.corebalt.isp.network.api;

import com.corebalt.isp.shared.outbox.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A line's usage in its current cycle passed its package's fair-use allowance or data cap (docs/09 step
 * 8b), noticed by the usage roll-up; published once per line, cycle and kind. The RADIUS view already
 * applies the limit at the next login; this event only speeds things up (E4): the API disconnects the
 * live session so it redials into the new profile, and the customer gets one SMS (Q15 default).
 * {@code usedBytes} and {@code limitBytes} are download plus upload.
 */
public record UsageThresholdReached(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID subscriptionId,
        UUID customerId,
        Kind kind,
        Instant cycleStartedAt,
        long usedBytes,
        long limitBytes)
        implements DomainEvent {

    public enum Kind {
        /** Past fup_bytes: THROTTLED until the next renewal. */
        FAIR_USE,
        /** Past data_cap_bytes: RESTRICTED until the next renewal (Q14 default). */
        DATA_CAP
    }
}
