-- S12 data: the activation cause of lines activated before V202610271001. The cutover import wrote an audit
-- row 'subscriptions.activated_by_import' for every line it activated (ADR 0014); every other activated line
-- was activated by a ledger-backed renewal. Lines never activated (PENDING, or cancelled before
-- activation, S9) keep no cause. No probe line existed before.
UPDATE subscriptions_subscription s
SET activation_cause = 'IMPORTED'
WHERE s.activation_cause IS NULL
  AND EXISTS (SELECT 1 FROM identity_audit_log a
              WHERE a.tenant_id = s.tenant_id AND a.target_id = s.id AND a.action = 'subscriptions.activated_by_import');

UPDATE subscriptions_subscription s
SET activation_cause = 'RENEWAL'
WHERE s.activation_cause IS NULL
  AND (s.activated_at IS NOT NULL OR s.state IN ('ACTIVE', 'PAUSED', 'SUSPENDED'));
