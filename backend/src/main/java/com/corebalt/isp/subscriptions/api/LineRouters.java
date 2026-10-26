package com.corebalt.isp.subscriptions.api;

import java.util.UUID;

/**
 * Which router a line belongs to, for the network module retiring a router (N10). A line's router is
 * bookkeeping: logins work through any router of the tenant (T3), so moving lines changes no session.
 * Current tenant only; call inside the retirement's transaction.
 */
public interface LineRouters {

    /** Lines on {@code routerId} that are not TERMINATED. */
    long liveLinesOn(UUID routerId);

    /** Moves every line on {@code from} that is not TERMINATED to {@code to}; returns how many moved. */
    int moveLiveLines(UUID from, UUID to);
}
