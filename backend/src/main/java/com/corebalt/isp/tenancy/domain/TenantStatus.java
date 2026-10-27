package com.corebalt.isp.tenancy.domain;

public enum TenantStatus {
    TRIAL,
    ACTIVE,
    SUSPENDED,
    /** Corebalt's own tenant (the probe tenant, ADR 0019): never listed, signed into or visited by jobs. */
    INTERNAL
}
