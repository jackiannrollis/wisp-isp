package com.corebalt.isp.subscriptions.api;

/**
 * The outcome of a renewal attempt: renewed, or left as it was for lack of balance (B5). The previous
 * profile and reason are the line's just before the renewal (the reason null when it was FULL or REJECT).
 */
public record RenewalResultDto(Outcome outcome, SubscriptionDto subscription, String previousAccessProfile,
                               String previousAccessReason) {

    public enum Outcome {
        RENEWED,
        INSUFFICIENT_BALANCE
    }
}
