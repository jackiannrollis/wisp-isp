package com.corebalt.isp.notifications.domain;

import java.time.Duration;

/**
 * What a Celcom Africa answer means for a message (ADR 0020):
 *
 * <ul>
 *   <li>200 accepted: SENT, not DELIVERED.
 *   <li>1003 Invalid mobile number: FAILED for good; never
 *       retried; the number is flagged for staff.
 *   <li>1004 low credits, 1001 invalid sender ID, 1006 invalid credentials, HTTP 401/403: the tenant's
 *       problem. The message waits (QUEUED_RETRY, hourly) and tenant staff and the platform are
 *       alerted.
 *   <li>Anything else (system/routing errors, HTTP 5xx, timeouts): retried
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
            case 200 -> messageId == null || messageId.isBlank()
                    ? new Transient("missing provider message ID") : new Sent(messageId, status);
            case 1003 -> new Permanent("INVALID_NUMBER", statusCode + " " + status);
            case 1004 -> new TenantProblem("SMS_CREDIT_EXHAUSTED", statusCode + " " + status);
            case 1001 -> new TenantProblem("SENDER_ID_INVALID", statusCode + " " + status);
            case 1006 -> new TenantProblem("SMS_AUTH_FAILED", statusCode + " " + status);
            default -> new Transient(statusCode + " " + status);
        };
    }

    static SendOutcome ofTransport(final int httpStatus, final String reason) {
        return httpStatus == 401 || httpStatus == 403 ? new TenantProblem("SMS_AUTH_FAILED", reason) : new Transient(reason);
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
