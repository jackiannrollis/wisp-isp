-- Data only (conventions.md, Migrations): the new permission finance.read (decisions.md 2026-10-20)
-- goes to every tenant's built-in Owner and Finance roles. Support and Technician do not get it.
-- Tokens issued before this carry the old list until their next refresh.
UPDATE identity_role
SET permissions = array_append(permissions, 'finance.read'), updated_at = now()
WHERE built_in AND name IN ('Owner', 'Finance') AND NOT ('finance.read' = ANY (permissions));
