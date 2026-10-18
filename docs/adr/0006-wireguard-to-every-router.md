# ADR 0006 · WireGuard tunnels to every tenant router

**Status:** Accepted
**Date:** 2026-10-17

## Context

RADIUS identifies a router by its source IP and shared secret, and CoA, the RouterOS REST API and SNMP all need the platform to reach the router. Tenant MikroTiks usually sit behind carrier NAT or on dynamic IPs, and exposing router management ports to the internet is unacceptable.

## Decision

Every router joins the platform over WireGuard during onboarding, using a generated RouterOS script that creates one interface with two peers: hub A at Hetzner and hub B in Nairobi. RADIUS clients are defined by tunnel IP on each hub, with a per-router secret. CoA, REST and SNMP travel only through the tunnels.

## Consequences

- NAT and changing public IPs stop mattering; a router's identity is its tunnel address and key.
- CoA and Disconnect always have a route to the router, which is what makes instant restore possible.
- No router port is exposed publicly; the attack surface is the WireGuard hubs.
- WireGuard handshake age doubles as a liveness signal for router monitoring.
- Hub capacity and key management become platform responsibilities; the onboarding script and a key-rotation runbook are v1 deliverables.
