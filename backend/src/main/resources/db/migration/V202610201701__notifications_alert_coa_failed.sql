-- A CoA or Disconnect that failed after its retries (N5, docs/08): one open alert per line, cleared
-- when a later request for that line is acknowledged.
ALTER TABLE notifications_alert DROP CONSTRAINT notifications_alert_kind_ck;
ALTER TABLE notifications_alert ADD CONSTRAINT notifications_alert_kind_ck
    CHECK (kind IN ('SMS_CREDIT_EXHAUSTED', 'SENDER_ID_INVALID', 'SMS_AUTH_FAILED', 'SMS_DEAD_LETTERED', 'ROUTER_OFFLINE',
                    'COA_FAILED'));
