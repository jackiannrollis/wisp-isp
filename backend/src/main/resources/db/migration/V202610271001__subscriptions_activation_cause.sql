-- S12 (decisions.md 2026-10-27): a line leaves PENDING for ACTIVE only by RENEWAL (a ledger-backed renewal,
-- S5), IMPORTED (the cutover import, S11) or PROBE (the platform-only probe setup, ADR 0019). The cause is
-- kept on the line, and the database enforces the list whatever the code does:
--
--   * a row that leaves PENDING for any state but TERMINATED (a cancel before activation, S9), or is
--     inserted in such a state, must carry a cause;
--   * a cause, once set, never changes;
--   * PROBE is accepted only on the probe tenant, whose id is fixed here and in tenancy.api.ProbeTenant.
--
-- Lines activated before this migration get their cause from V202610271002.
ALTER TABLE subscriptions_subscription
    ADD COLUMN activation_cause text,
    ADD CONSTRAINT subscriptions_subscription_activation_cause_ck
        CHECK (activation_cause IN ('RENEWAL', 'IMPORTED', 'PROBE')),
    ADD CONSTRAINT subscriptions_subscription_probe_tenant_ck
        CHECK (activation_cause IS DISTINCT FROM 'PROBE' OR tenant_id = '0191f0be-0000-7000-8000-000000000001'::uuid);

CREATE FUNCTION subscriptions_activation_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.activation_cause IS NOT NULL
            AND NEW.activation_cause IS DISTINCT FROM OLD.activation_cause THEN
        RAISE EXCEPTION 'S12: the activation cause of line % never changes', NEW.id;
    END IF;
    IF NEW.state NOT IN ('PENDING', 'TERMINATED') AND NEW.activation_cause IS NULL
            AND (TG_OP = 'INSERT' OR OLD.state = 'PENDING') THEN
        RAISE EXCEPTION 'S12: line % leaves PENDING without an activation cause (RENEWAL, IMPORTED or PROBE)', NEW.id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER subscriptions_activation_guard BEFORE INSERT OR UPDATE ON subscriptions_subscription
    FOR EACH ROW EXECUTE FUNCTION subscriptions_activation_guard();
