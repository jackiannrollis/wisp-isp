-- ADR 0020: Celcom Africa replaces Africa's Talking. Keep old columns for rolling-release
-- compatibility and historical settings; never reinterpret an old API key as a Celcom key.
ALTER TABLE notifications_settings ADD COLUMN sms_provider text NOT NULL DEFAULT 'AFRICAS_TALKING';
ALTER TABLE notifications_settings ADD COLUMN celcom_partner_id text;
ALTER TABLE notifications_settings ADD CONSTRAINT notifications_settings_provider_ck
    CHECK (sms_provider IN ('AFRICAS_TALKING', 'CELCOM_AFRICA'));
ALTER TABLE notifications_settings ADD CONSTRAINT notifications_settings_celcom_partner_ck
    CHECK (sms_provider <> 'CELCOM_AFRICA' OR (celcom_partner_id IS NOT NULL AND length(trim(celcom_partner_id)) BETWEEN 1 AND 100));

-- The route lookup runs in platform scope, so its provider discriminator cannot be read by
-- joining an RLS-protected tenant table. Legacy callbacks never update Celcom messages.
ALTER TABLE notifications_callback_route ADD COLUMN sms_provider text NOT NULL DEFAULT 'AFRICAS_TALKING';
ALTER TABLE notifications_callback_route ADD CONSTRAINT notifications_callback_route_provider_ck
    CHECK (sms_provider IN ('AFRICAS_TALKING', 'CELCOM_AFRICA'));

ALTER TABLE tenancy_tenant_secret DROP CONSTRAINT tenancy_tenant_secret_kind_ck;
ALTER TABLE tenancy_tenant_secret ADD CONSTRAINT tenancy_tenant_secret_kind_ck CHECK (kind IN (
    'MPESA_CONSUMER_KEY', 'MPESA_CONSUMER_SECRET', 'MPESA_PASSKEY',
    'MPESA_INITIATOR_NAME', 'MPESA_SECURITY_CREDENTIAL', 'MPESA_CALLBACK_TOKEN',
    'SMS_API_KEY', 'SMS_CALLBACK_TOKEN', 'CELCOM_SMS_API_KEY'));
