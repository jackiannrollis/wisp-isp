package com.corebalt.isp.subscriptions.domain;

/**
 * S12: the only ways a line leaves PENDING for ACTIVE, kept on the line ({@code activation_cause}). The
 * database refuses an activation without one and PROBE outside the probe tenant (V202610271001).
 * Returning an activated line to ACTIVE (resume, S7; lifting a suspension, S8) is not an activation.
 */
public enum ActivationCause {
    /** A ledger-backed renewal (S5, B5). */
    RENEWAL,
    /** The cutover import, within the tenant's open cutover window (S11, ADR 0014). */
    IMPORTED,
    /** The platform-only probe setup, on the probe tenant only (ADR 0019). */
    PROBE
}
