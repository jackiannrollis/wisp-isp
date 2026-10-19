package com.corebalt.isp.subscriptions.api;

/** A new line and its first PPPoE password, shown only this once (ADR 0009). */
public record LineOpenedDto(SubscriptionDto subscription, String pppoePassword) {}
