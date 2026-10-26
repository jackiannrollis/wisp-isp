-- Kept PPPoE passwords (ADR 0014): the cutover import stores the password a customer's router
-- already dials with, which an old system may have made short. The platform still generates every
-- password it issues at 16 characters and every router secret at its own length, so the check keeps
-- its floor for router secrets and only lets a line's password be 1 to 64 characters.
ALTER TABLE network_radius_credential
    DROP CONSTRAINT network_radius_credential_secret_ck,
    ADD CONSTRAINT network_radius_credential_secret_ck CHECK (
        secret IS NULL
        OR (router_id IS NOT NULL AND length(secret) >= 12)
        OR (subscription_id IS NOT NULL AND length(secret) BETWEEN 1 AND 64)
    );
