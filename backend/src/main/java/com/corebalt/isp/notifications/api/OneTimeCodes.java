package com.corebalt.isp.notifications.api;

import java.util.UUID;

/**
 * Sends a sign-in code by SMS at once, outside the queue (ADR 0010, portal OTP). The code is never
 * stored: the message log keeps the body with the code masked, so staff reading it cannot sign in as
 * the customer (M3). Nothing is retried; the customer asks for a new code.
 */
public interface OneTimeCodes {

    enum Delivery { SENT, NOT_CONFIGURED, FAILED }

    /** Runs in the caller's tenant context; must not be called inside a transaction (it calls the provider). */
    Delivery sendSignInCode(UUID customerId, String e164, String code);
}
