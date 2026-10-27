package com.corebalt.isp.subscriptions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The probe tenant's lines (ADR 0019), for the platform's {@code probe-setup} command only: every method
 * refuses unless the current tenant is the probe tenant. A line opened here is activated with cause PROBE
 * (S12): the one path to ACTIVE that needs no payment besides the cutover import.
 */
public interface ProbeLines {

    List<ProbeLine> lines();

    /** Opens a line for the customer on the router and activates it with this expiry; returns it. */
    ProbeLine open(UUID customerId, UUID packageId, UUID routerId, Instant expiresAt);

    record ProbeLine(UUID id, String pppoeUsername, String state, Instant expiresAt) {}
}
