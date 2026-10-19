-- The SMS delivery-report URL token is a tenant secret too (like the M-Pesa callback token).
-- V202610201200 is applied and must not change, so the kind list is widened here.

ALTER TABLE tenancy_tenant_secret DROP CONSTRAINT tenancy_tenant_secret_kind_ck;
ALTER TABLE tenancy_tenant_secret ADD CONSTRAINT tenancy_tenant_secret_kind_ck CHECK (kind IN (
    'MPESA_CONSUMER_KEY', 'MPESA_CONSUMER_SECRET', 'MPESA_PASSKEY',
    'MPESA_INITIATOR_NAME', 'MPESA_SECURITY_CREDENTIAL', 'MPESA_CALLBACK_TOKEN',
    'SMS_API_KEY', 'SMS_CALLBACK_TOKEN'));
