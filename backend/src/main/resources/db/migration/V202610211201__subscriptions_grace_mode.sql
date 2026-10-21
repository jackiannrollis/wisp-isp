-- S6 widened (decisions.md 2026-10-21): a renewal copies the tenant's grace_mode onto the line, as it
-- already copies grace_days, so a tenant changing its grace policy never alters a cycle already paid
-- for. Null on lines renewed before this: they keep following the tenant's mode until they renew.
ALTER TABLE subscriptions_subscription
    ADD COLUMN grace_mode text,
    ADD CONSTRAINT subscriptions_subscription_grace_mode_ck CHECK (grace_mode IS NULL OR grace_mode IN ('FULL_SPEED', 'THROTTLED'));
