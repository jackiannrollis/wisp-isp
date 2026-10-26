-- Router onboarding (docs/06 Router onboarding script; ADR 0013). The platform generates the router's
-- WireGuard key pair, hands the private key over once in the onboarding script and keeps the public
-- key here, so the hub's peer list can be rebuilt from the database. The ISP's addressing on the
-- router is what the script needs beside it. All nullable: a router registered before this gets them
-- when its first script is generated. The REST and SNMP credentials the script sets stay where they
-- were: the REST login envelope-encrypted on the router (rest_password_enc), the SNMP community not
-- kept (nothing reads SNMP yet; the next script sets a new one).
ALTER TABLE network_router
    ADD COLUMN wg_public_key text,
    ADD COLUMN pppoe_interface text,
    ADD COLUMN ppp_local_address inet,
    ADD COLUMN pool_full text,
    ADD COLUMN pool_restricted text,
    ADD COLUMN management_cidrs text,
    ADD COLUMN onboarding_script_generated_at timestamptz,
    ADD CONSTRAINT network_router_wg_public_key_uk UNIQUE (wg_public_key),
    ADD CONSTRAINT network_router_wg_public_key_ck CHECK (wg_public_key IS NULL OR wg_public_key ~ '^[A-Za-z0-9+/]{43}=$'),
    ADD CONSTRAINT network_router_ppp_local_address_ck CHECK (ppp_local_address IS NULL OR family(ppp_local_address) = 4);
