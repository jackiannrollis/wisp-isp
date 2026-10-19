package com.corebalt.isp.subscriptions.api;

/** The outcome of a renewal attempt: renewed, or left as it was for lack of balance (B5). */
public record RenewalResultDto(Outcome outcome, SubscriptionDto subscription, String previousAccessProfile) {

    public enum Outcome {
        RENEWED,
        INSUFFICIENT_BALANCE
    }
}
