package com.corebalt.isp.shared.audit;

import java.util.UUID;

/**
 * Writes an append-only audit row (A1) inside the caller's transaction, for the current tenant and
 * actor. Callers describe what changed; never pass secrets, passwords or PINs in the states (A3).
 */
public interface AuditTrail {

    /** The actor comes from the request's access token (an impersonation records the platform admin). */
    void record(AuditEntry entry);

    /** For steps with no access token yet, such as a sign-in: the actor is named explicitly. */
    void recordAs(String actorType, UUID actorId, AuditEntry entry);
}
