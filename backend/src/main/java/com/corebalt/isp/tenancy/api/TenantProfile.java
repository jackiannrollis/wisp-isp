package com.corebalt.isp.tenancy.api;

/** Facts about the current tenant that other modules need (T1: always the context's tenant). */
public interface TenantProfile {

    /** The tenant code that prefixes customer numbers and PPPoE usernames, e.g. {@code CB} (T2). */
    String currentTenantCode();
}
