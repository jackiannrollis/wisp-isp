# ADR 0013 · Router onboarding: the platform generates the router's keys and the hub learns peers from the database

**Status:** Accepted; the sync's "never removed" amended by [ADR 0015](0015-router-retirement.md) (a retired router's peer is removed)
**Date:** 2026-10-26

## Context

docs/06 describes an onboarding script generated per router from the admin dashboard. Until now only the network lab's template existed, rendered by hand, and the hub's peer list was kept only in files on the host: a router's WireGuard public key lived nowhere else, so a lost host meant re-onboarding every router unless the files had been exported to escrow (the first failover-drill runbook asked exactly that). The RADIUS secret, the REST password and the SNMP community had to be carried to the technician in a file beside the script.

## Decision

- The API generates the script (`POST /api/v1/routers/{id}/onboarding-script`, `network.write`) from the template in `backend/src/main/resources/routeros/onboarding.rsc.tmpl`, which the lab also renders. Each generation makes the router a **new WireGuard key pair**: the private key is in the script only, the public key is stored on the router (`network_router.wg_public_key`, unique). The RADIUS secret is regenerated at the same time (ADR 0009: shown once, loaded by FreeRADIUS at its next reload), the REST login is set to `corebalt-api` with a new password (envelope-encrypted for the tunnel worker, ADR 0011) and a new SNMP community is written to the router and not kept (nothing reads SNMP yet). The script is returned once with `Cache-Control: no-store` and never stored; the audit row carries the public key and the addressing, never a secret.
- The ISP's addressing on the router (PPPoE interface, the PPP local address, the FULL and RESTRICTED pools, the management networks) is entered with the request and kept on the router for the next generation. The platform's side comes from the host's environment: the hub's public key is **read from a mounted file at each request** (`/var/lib/wireguard/hub.pub` on a host, the wireguard volume locally), never copied into configuration, so a hub key restored from escrow is used at once; the hub's endpoint is the DNS name `hub-a.<domain>`, its address and prefix, the public address `pay.<domain>` resolves to, and NTP come from the inventory.
- The hub's peer list is derived from the database: a host-side sync reads every router's name, public key and tunnel address and keeps `wg0`'s peers equal to it, so a rebuilt host has its peers back once the stack is up, and `corebalt-wg-hub export-peers` becomes a diagnostic rather than something to escrow.
- The script is idempotent on a live router: pools and the PPP profile are updated in place, and a PPPoE server is added only where the interface has none, so an ISP's existing server is kept until the cutover.

## Consequences

- Nothing about a router has to be read back from it or carried in a side file; the dialog's one download holds everything, and the technician's only hand-offs are the script and the cutover.
- A router's identity on the hub is reproducible from backups (T4 is unchanged: the public key is public; the private key is nowhere after the script).
- Generating a script rotates the router's RADIUS secret: FreeRADIUS holds the old one until reloaded and the router until it runs the script, so the two happen together (the onboarding runbook).
- The private key crosses the platform once, in the script, like the RADIUS secret always has (ADR 0009); the script file is as sensitive as before and deleted after use.
- RouterOS accepting `/interface wireguard set private-key=`, the conditional PPPoE step and the hub's DNS name being re-resolved are expected RouterOS 7 behaviour, unproven until the network lab runs on real routers.
