package com.corebalt.isp.subscriptions.api;

import java.util.UUID;

/**
 * Port for the routers a line may terminate on, implemented by the network module (same reason as
 * {@link LineCredentialStore}). Scoped to the current tenant (T1, T3).
 */
public interface RouterDirectory {

    /** The router exists and is not retired (N10): a new line may terminate on it. */
    boolean exists(UUID routerId);
}
