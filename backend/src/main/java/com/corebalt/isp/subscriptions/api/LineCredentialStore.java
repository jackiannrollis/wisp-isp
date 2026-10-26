package com.corebalt.isp.subscriptions.api;

import java.util.UUID;

/**
 * Port for a line's PPPoE password, implemented by the network module, which owns
 * {@code network_radius_credential} (ADR 0009). It lives here because network already depends on
 * this module's events, so subscriptions may not depend on network (conventions.md, Backend).
 *
 * <p>Passwords are generated behind the port, returned exactly once for the show-once screen and
 * never readable again by the application (T4 exception, ADR 0009). Call inside the subscription's
 * transaction so the line and its credential commit together.
 */
public interface LineCredentialStore {

    /** Generates and stores the first password of a new line; returns it once. */
    String issue(UUID subscriptionId);

    /**
     * Stores the password a new line keeps from the ISP's old system (the cutover import, ADR 0014):
     * the customer's router already has it, so it is never returned, logged or audited by value.
     */
    void keep(UUID subscriptionId, String password);

    /** Replaces the password; the old one stops working at the next login. Returns the new one once. */
    String regenerate(UUID subscriptionId);

    /** Removes the password so the line can no longer log in; the row is kept. */
    void revoke(UUID subscriptionId);
}
