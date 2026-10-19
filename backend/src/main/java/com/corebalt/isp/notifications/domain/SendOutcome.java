package com.corebalt.isp.notifications.domain;

import java.time.Duration;

/**
 * What an Africa's Talking answer means for a message (decided with the product owner, step 7):
 *
 * <ul>
 *   <li>100 Processed, 101 Sent, 102 Queued: SENT.
 *   <li>403 InvalidPhoneNumber, 404 UnsupportedNumberType, 406 UserInBlacklist: FAILED for good; never
 *       retried; the number is flagged for staff.
 *   <li>405 InsufficientBalance, 402 InvalidSenderId, HTTP 401 (API key refused): the tenant's
 *       problem. The message waits (QUEUED_RETRY, hourly) and tenant staff and the platform are
 *       alerted.
 *   <li>Anything else (401 RiskHold, 407 CouldNotRoute, 500, 501, 502, HTTP 5xx, timeouts): retried
 *       after 1, 5 and 30 minutes, then DEAD (dead-lettered, alerted).
 * </ul>
 */
public sealed interface SendOutcome {

    int MAX_ATTEMPTS = 4;
    Duration TENANT_RETRY = Duration.ofHours(1);

    record Sent(String messageId, String providerStatus) implements SendOutcome {}

    record Permanent(String flag, String reason) implements SendOutcome {}

    record TenantProblem(String alertKind, String reason) implements SendOutcome {}

    record Transient(String reason) implements SendOutcome {}

    static SendOutcome ofRecipient(final int statusCode, final String status, final String messageId) {
        return switch (statusCode) {
            case 100, 101, 102 -> new Sent(messageId, status);
            case 403, 404 -> new Permanent("INVALID_NUMBER", statusCode + " " + status);
            case 406 -> new Permanent("BLACKLISTED", statusCode + " " + status);
            case 405 -> new TenantProblem("SMS_CREDIT_EXHAUSTED", statusCode + " " + status);
            case 402 -> new TenantProblem("SENDER_ID_INVALID", statusCode + " " + status);
            default -> new Transient(statusCode + " " + status);
        };
    }

    static SendOutcome ofTransport(final int httpStatus, final String reason) {
        return httpStatus == 401 ? new TenantProblem("SMS_AUTH_FAILED", reason) : new Transient(reason);
    }

    /** Wait before the next attempt after {@code attempts} failed ones (1, 5, 30 minutes). */
    static Duration backoff(final int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            default -> Duration.ofMinutes(30);
        };
    }
}
