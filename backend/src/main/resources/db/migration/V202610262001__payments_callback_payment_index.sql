-- Gate 1 (ADR 0018) looks callbacks up by the payment they produced: when a payment's first callback
-- arrived, and which payments came by callback at all.
CREATE INDEX payments_mpesa_callback_payment_ix ON payments_mpesa_callback (tenant_id, payment_id)
    WHERE payment_id IS NOT NULL;
