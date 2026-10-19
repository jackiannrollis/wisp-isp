package com.corebalt.isp.subscriptions.domain;

/** The only stored states (S1); EXPIRING, GRACE, RESTRICTED and the access profile are derived. */
public enum SubscriptionState {
    PENDING,
    ACTIVE,
    PAUSED,
    SUSPENDED,
    TERMINATED
}
