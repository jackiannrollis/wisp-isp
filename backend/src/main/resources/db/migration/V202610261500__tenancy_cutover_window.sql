-- The tenant's cutover window (ADR 0014, S11): while it is open, the customer import may activate
-- lines with the expiry their customers already paid for in the ISP's old system. A Corebalt
-- platform admin opens it for at most 30 days; it closes by itself at cutover_window_until, or
-- earlier when the console closes it (null). Nothing else reads it.
ALTER TABLE tenancy_tenant ADD COLUMN cutover_window_until timestamptz;
