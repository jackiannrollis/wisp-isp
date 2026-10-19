package com.corebalt.isp.subscriptions.domain;

/** Derived at read time, never stored (S1, S2). */
public enum AccessProfile {
    FULL,
    THROTTLED,
    RESTRICTED,
    REJECT
}
