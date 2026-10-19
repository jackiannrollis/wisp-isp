-- CoA and Disconnect from the tunnel worker (N5, ADR 0011). isp_worker becomes the second reader of
-- router RADIUS secrets after FreeRADIUS (ADR 0009), through radius_client_v3 only and only these
-- columns. It reads them when it sends, never caches them. The view runs with its owner's rights, so
-- the worker's query filters by tenant itself. Which sessions to change is decided by the API from
-- radacct; the worker gets no grant on radacct or on subscriptions.
GRANT SELECT (tenant_id, nasname, secret) ON radius_client_v3 TO isp_worker;
