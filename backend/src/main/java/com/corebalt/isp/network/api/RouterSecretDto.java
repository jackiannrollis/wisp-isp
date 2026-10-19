package com.corebalt.isp.network.api;

/**
 * A router with its newly generated RADIUS shared secret, returned once when the router is
 * registered or its secret regenerated, for the onboarding script. It cannot be read again (ADR 0009).
 */
public record RouterSecretDto(RouterDto router, String radiusSecret) {}
