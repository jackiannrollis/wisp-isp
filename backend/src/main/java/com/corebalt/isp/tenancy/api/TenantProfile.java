package com.corebalt.isp.tenancy.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/** Facts about the current tenant that other modules need (T1: always the context's tenant). */
public interface TenantProfile {

    /** The tenant code that prefixes customer numbers and PPPoE usernames, e.g. {@code CB} (T2). */
    String currentTenantCode();

    /** The current tenant's subscription policy (docs/07 per-tenant configuration). */
    TenantPolicy currentPolicy();

    /**
     * The end of the tenant's cutover window (ADR 0014, S11) when the console opened one; empty
     * otherwise. Whether it is open is a comparison with the clock: it closes by itself.
     */
    Optional<Instant> currentCutoverWindowUntil();

    /**
     * {@code graceMode} is {@code FULL_SPEED} or {@code THROTTLED}; {@code partialPaymentRule} is
     * {@code WAIT_FOR_FULL} or {@code PRO_RATA_DAYS} (docs/05 Partial payments); {@code zone} is for day
     * boundaries and display only (conventions.md, Time).
     */
    record TenantPolicy(
            String code, ZoneId zone, int defaultGraceDays, String graceMode, int maxPauseDays, String partialPaymentRule) {}
}
