package com.corebalt.isp.shared.tenant;

/** T1: a missing tenant context is an error, never "all tenants". */
public class TenantContextMissingException extends IllegalStateException {

    public TenantContextMissingException(final String message) {
        super("T1: " + message);
    }
}
