-- The last CoA or Disconnect outcome per line (N5), written by the API from the worker's
-- SessionReauthorized and SessionReauthorizationFailed events. The portal's pay page uses it to say
-- "Internet restored" (docs/06 Restricted mode). Only the newest outcome is kept.
CREATE TABLE network_line_coa (
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subscription_id  uuid        NOT NULL,
    last_coa_at      timestamptz NOT NULL,
    last_coa_action  text        NOT NULL,
    last_coa_result  text        NOT NULL,
    last_event_id    uuid        NOT NULL,
    PRIMARY KEY (tenant_id, subscription_id),
    CONSTRAINT network_line_coa_action_ck CHECK (last_coa_action IN ('COA', 'DISCONNECT')),
    CONSTRAINT network_line_coa_result_ck CHECK (last_coa_result IN ('ACK', 'SESSION_GONE', 'FAILED'))
);

ALTER TABLE network_line_coa ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_line_coa_tenant_isolation ON network_line_coa
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON network_line_coa TO isp_app;
