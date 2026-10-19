-- Routers, the RADIUS NASes (docs/03 network). Step 3 keeps only what FreeRADIUS needs: the tunnel
-- address on hub A. Sites, the hub B address, WireGuard keys, REST and SNMP credentials and
-- monitoring status arrive with router onboarding. The per-router RADIUS secret lives in
-- network_radius_credential (ADR 0009).

CREATE TABLE network_router (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    name        text        NOT NULL,
    tunnel_ip_a inet        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT network_router_tenant_id_uk UNIQUE (tenant_id, id),
    -- FreeRADIUS identifies the NAS by this address on the hub, across every tenant (N6, ADR 0006).
    CONSTRAINT network_router_tunnel_ip_a_uk UNIQUE (tunnel_ip_a),
    CONSTRAINT network_router_tunnel_ip_a_ck CHECK (family(tunnel_ip_a) = 4 AND masklen(tunnel_ip_a) = 32)
);

CREATE UNIQUE INDEX network_router_tenant_name_uk ON network_router (tenant_id, lower(name));

ALTER TABLE network_router ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_router_tenant_isolation ON network_router
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON network_router TO isp_app;
