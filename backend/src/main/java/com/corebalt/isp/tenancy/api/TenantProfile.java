package com.corebalt.isp.tenancy.api;

import java.time.ZoneId;

/** Facts about the current tenant that other modules need (T1: always the context's tenant). */
public interface TenantProfile {

    /** The tenant code that prefixes customer numbers and PPPoE usernames, e.g. {@code CB} (T2). */
    String currentTenantCode();

    /** The current tenant's subscription policy (docs/07 per-tenant configuration). */
    TenantPolicy currentPolicy();

    /**
     * {@code graceMode} is {@code FULL_SPEED} or {@code THROTTLED}; {@code zone} is for day
     * boundaries and display only (conventions.md, Time).
     */
    record TenantPolicy(String code, ZoneId zone, int defaultGraceDays, String graceMode, int maxPauseDays) {}
}
