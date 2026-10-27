package com.corebalt.isp.tenancy.api;

import java.util.UUID;

/**
 * The probe tenant (ADR 0019): Corebalt's own INTERNAL tenant holding the two lines of the synthetic RADIUS
 * login probe, created by the platform-only {@code probe-setup} command. Its id is fixed: the database checks
 * the PROBE activation cause against the same literal (S12) and the probe credential view selects by it (T4),
 * so neither can be pointed at a real tenant. It is never listed, found by code or subdomain, signed into or
 * visited by a scheduled job.
 */
public final class ProbeTenant {

    /** Also a literal in V202610271001 and V202610271003; ActivationCauseIT checks they agree. */
    public static final UUID ID = UUID.fromString("0191f0be-0000-7000-8000-000000000001");
    public static final String CODE = "CBPR";
    public static final String NAME = "Corebalt Probe";
    public static final String SUBDOMAIN = "probe";

    private ProbeTenant() {}

    public static boolean is(final UUID tenantId) {
        return ID.equals(tenantId);
    }
}
