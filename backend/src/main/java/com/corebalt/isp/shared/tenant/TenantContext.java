package com.corebalt.isp.shared.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Thread-bound tenant context (T1). Resolved once per request or event, read by the transaction
 * manager to set {@code app.tenant_id} for PostgreSQL row-level security.
 */
public final class TenantContext {

    public static final String MDC_KEY = "tenantId";

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<TenantScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current tenant ID; throws when there is no tenant (including platform scope). */
    public static UUID requireTenantId() {
        if (CURRENT.get() instanceof TenantScope.Tenant(final UUID tenantId)) {
            return tenantId;
        }
        throw new TenantContextMissingException("no tenant context on this thread");
    }

    public static TenantScope requireScope() {
        final TenantScope scope = CURRENT.get();
        if (scope == null) {
            throw new TenantContextMissingException("no tenant context on this thread");
        }
        return scope;
    }

    public static void runAsTenant(final UUID tenantId, final Runnable work) {
        callAs(
                new TenantScope.Tenant(tenantId),
                () -> {
                    work.run();
                    return null;
                });
    }

    public static <T> T callAsTenant(final UUID tenantId, final Supplier<T> work) {
        return callAs(new TenantScope.Tenant(tenantId), work);
    }

    /** Runs without a tenant; use {@link TenantIterator} to reach tenant data from here. */
    public static <T> T callAsPlatform(final String reason, final Supplier<T> work) {
        return callAs(new TenantScope.Platform(reason), work);
    }

    private static <T> T callAs(final TenantScope scope, final Supplier<T> work) {
        final TenantScope previous = CURRENT.get();
        final String previousMdc = MDC.get(MDC_KEY);
        set(scope);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                CURRENT.set(previous);
                restoreMdc(previousMdc);
            }
        }
    }

    /** For filters and listeners that manage the lifecycle themselves; pair with {@link #clear()}. */
    public static void set(final TenantScope scope) {
        CURRENT.set(scope);
        if (scope instanceof TenantScope.Tenant(final UUID tenantId)) {
            MDC.put(MDC_KEY, tenantId.toString());
        } else {
            MDC.put(MDC_KEY, "platform");
        }
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    private static void restoreMdc(final String value) {
        if (value == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, value);
        }
    }
}
