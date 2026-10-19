package com.corebalt.isp.shared.audit;

/**
 * Writes an append-only audit row (A1) inside the caller's transaction, for the current tenant and
 * actor. Callers describe what changed; never pass secrets, passwords or PINs in the states (A3).
 */
public interface AuditTrail {

    void record(AuditEntry entry);
}
