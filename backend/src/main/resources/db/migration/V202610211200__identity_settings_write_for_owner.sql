-- Data only (conventions.md, Migrations): the new permission settings.write (decisions.md 2026-10-21)
-- goes to every tenant's built-in Owner role, which alone holds it. Tokens issued before this carry
-- the old list until their next refresh.
UPDATE identity_role
SET permissions = array_append(permissions, 'settings.write'), updated_at = now()
WHERE built_in AND name = 'Owner' AND NOT ('settings.write' = ANY (permissions));
