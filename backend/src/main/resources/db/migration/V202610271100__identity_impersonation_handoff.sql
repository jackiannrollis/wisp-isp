-- The impersonation hand-off (ADR 0017, Q17 resolved): starting an impersonation in the console returns a
-- single-use code, valid 60 seconds, which the admin app exchanges for the impersonation token. Only the
-- code's SHA-256 is kept; the token is issued at the exchange and never stored. A code is used once
-- (handoff_used_at), never after its expiry, and never for an impersonation already ended.
ALTER TABLE identity_impersonation
    ADD COLUMN handoff_code_hash   text,
    ADD COLUMN handoff_expires_at  timestamptz,
    ADD COLUMN handoff_used_at     timestamptz,
    ADD CONSTRAINT identity_impersonation_handoff_code_uk UNIQUE (handoff_code_hash);

GRANT UPDATE (handoff_used_at) ON identity_impersonation TO isp_app;
