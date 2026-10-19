package com.corebalt.isp.subscriptions.api;

import java.util.UUID;

/**
 * A line's PPPoE login with its newly generated password, returned once at creation or regeneration
 * for the show-once screen. It can never be read again, only regenerated (ADR 0009).
 */
public record PppoeCredentialDto(UUID subscriptionId, String pppoeUsername, String pppoePassword) {}
