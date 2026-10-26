-- Router retirement (ADR 0015, N10): a router replaced, stolen or taken out of service is retired, once
-- and for good. Retiring revokes its RADIUS secret (radius_client_v3 lists only routers with one, so
-- FreeRADIUS stops answering it at its next reload), clears its REST login (the tunnel worker stops
-- polling it), closes its open sessions, moves its lines to a replacement, and the host's routersync
-- removes its WireGuard peer from the hub within a minute.
--
-- The row stays: accounting history, audit rows and lines that ended on it point at it. Its tunnel
-- address is never reused (the RADIUS view lists a tenant's routers by address, so a reused one would
-- answer twice); its name may be, by the router that replaces it.
ALTER TABLE network_router
    ADD COLUMN retired_at            timestamptz,
    ADD COLUMN retired_reason        text,
    ADD COLUMN replaced_by_router_id uuid,
    ADD CONSTRAINT network_router_retired_ck
        CHECK ((retired_at IS NULL) = (retired_reason IS NULL) AND (retired_at IS NOT NULL OR replaced_by_router_id IS NULL)),
    ADD CONSTRAINT network_router_replaced_by_fk FOREIGN KEY (tenant_id, replaced_by_router_id)
        REFERENCES network_router (tenant_id, id);

DROP INDEX network_router_tenant_name_uk;
CREATE UNIQUE INDEX network_router_tenant_name_uk ON network_router (tenant_id, lower(name)) WHERE retired_at IS NULL;
