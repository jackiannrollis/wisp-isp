-- The router's own view of its PPPoE sessions (docs/06 Accounting, decisions.md 2026-10-20): the tunnel
-- worker reads /ppp/active with each poll and keeps the latest snapshot here, so the router page can
-- show radacct's open sessions beside it without the API ever talking to a router (ADR 0011).
ALTER TABLE network_router
    ADD COLUMN ppp_active_count  integer,
    ADD COLUMN ppp_active_users  text[],
    ADD COLUMN ppp_active_at     timestamptz;

-- Pinned exactly by DatabaseGrantsIT.
GRANT UPDATE (ppp_active_count, ppp_active_users, ppp_active_at) ON network_router TO isp_worker;
