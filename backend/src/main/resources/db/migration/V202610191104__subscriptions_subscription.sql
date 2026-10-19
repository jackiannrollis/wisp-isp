-- The service line (docs/03 subscriptions), minimal for step 3: enough for the RADIUS view to read
-- real tables. Step 4 adds the lifecycle (pause, billing day, pending package, activation and
-- termination times), AccessPolicy and SubscriptionStateChanged. The PPPoE password lives in
-- network_radius_credential, written only by the network module (ADR 0009).

CREATE TABLE subscriptions_subscription (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    customer_id     uuid        NOT NULL,
    package_id      uuid        NOT NULL,
    router_id       uuid        NOT NULL,
    pppoe_username  text        NOT NULL,
    static_ip       inet,
    mac_address     macaddr,
    state           text        NOT NULL DEFAULT 'PENDING',
    expires_at      timestamptz,
    grace_days      integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT subscriptions_subscription_tenant_id_uk UNIQUE (tenant_id, id),
    -- Composite keys: a line may only point at its own tenant's customer, package and router (T1, T3).
    CONSTRAINT subscriptions_subscription_customer_fk FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers_customer (tenant_id, id),
    CONSTRAINT subscriptions_subscription_package_fk FOREIGN KEY (tenant_id, package_id)
        REFERENCES packages_package (tenant_id, id),
    CONSTRAINT subscriptions_subscription_router_fk FOREIGN KEY (tenant_id, router_id)
        REFERENCES network_router (tenant_id, id),
    -- T2: CB001238 for the first line, CB001238-2 for the second; unique across the platform.
    CONSTRAINT subscriptions_subscription_pppoe_username_uk UNIQUE (pppoe_username),
    CONSTRAINT subscriptions_subscription_pppoe_username_ck
        CHECK (pppoe_username ~ '^[A-Z]{2,4}[0-9]{6}(-[0-9]+)?$'),
    -- S1: only the stored states.
    CONSTRAINT subscriptions_subscription_state_ck
        CHECK (state IN ('PENDING', 'ACTIVE', 'PAUSED', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT subscriptions_subscription_expires_ck
        CHECK (state NOT IN ('ACTIVE', 'PAUSED') OR expires_at IS NOT NULL),
    CONSTRAINT subscriptions_subscription_grace_days_ck CHECK (grace_days >= 0),
    CONSTRAINT subscriptions_subscription_static_ip_ck
        CHECK (static_ip IS NULL OR (family(static_ip) = 4 AND masklen(static_ip) = 32))
);

CREATE INDEX subscriptions_subscription_tenant_customer_ix ON subscriptions_subscription (tenant_id, customer_id);
CREATE INDEX subscriptions_subscription_tenant_router_ix ON subscriptions_subscription (tenant_id, router_id);

ALTER TABLE subscriptions_subscription ENABLE ROW LEVEL SECURITY;
CREATE POLICY subscriptions_subscription_tenant_isolation ON subscriptions_subscription
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON subscriptions_subscription TO isp_app;
