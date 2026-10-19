package com.corebalt.isp.identity.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One audited change: {@code action} like {@code subscriptions.pppoe_password_regenerated}, the
 * target, and optional before and after states (small maps of non-secret fields).
 */
public record AuditEntry(
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> before,
        Map<String, Object> after,
        String reason) {

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
    }

    public static AuditEntry of(final String action, final String targetType, final UUID targetId) {
        return new AuditEntry(action, targetType, targetId, null, null, null);
    }

    public static AuditEntry of(
            final String action, final String targetType, final UUID targetId, final Map<String, Object> after) {
        return new AuditEntry(action, targetType, targetId, null, after, null);
    }
}
