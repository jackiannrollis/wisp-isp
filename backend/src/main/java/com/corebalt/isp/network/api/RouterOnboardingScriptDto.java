package com.corebalt.isp.network.api;

/**
 * A router's onboarding script, returned once: it holds the router's new WireGuard private key, its
 * new RADIUS secret, REST password and SNMP community, none of which can be read again (ADR 0009,
 * ADR 0013). {@code fileName} is what to save it as before pasting it into the router's terminal.
 */
public record RouterOnboardingScriptDto(RouterDto router, String fileName, String script) {}
