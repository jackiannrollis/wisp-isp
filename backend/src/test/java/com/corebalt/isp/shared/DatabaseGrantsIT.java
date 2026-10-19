package com.corebalt.isp.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every privilege the restricted roles hold, read from the catalogue (conventions.md, Database;
 * N2; ADR 0009, ADR 0011). isp_worker and isp_radius are pinned exactly: a migration that grants
 * them anything more fails here. For isp_app the sensitive grants are pinned: append-only tables,
 * accounting, RADIUS secrets and the SECURITY DEFINER functions.
 */
class DatabaseGrantsIT extends IntegrationTest {

    private static final String PRIVILEGES = """
            with r as (select oid from pg_roles where rolname = :role)
            select c.relname || ':' || a.privilege_type
            from pg_class c, aclexplode(c.relacl) a, r
            where c.relnamespace = 'public'::regnamespace and a.grantee = r.oid
            union all
            select c.relname || '.' || att.attname || ':' || a.privilege_type
            from pg_class c join pg_attribute att on att.attrelid = c.oid, aclexplode(att.attacl) a, r
            where c.relnamespace = 'public'::regnamespace and a.grantee = r.oid
            union all
            select p.proname || '():' || a.privilege_type
            from pg_proc p, aclexplode(p.proacl) a, r
            where p.pronamespace = 'public'::regnamespace and a.grantee = r.oid
            order by 1
            """;

    @Test
    void ADR0011_ADR0009_theTunnelWorkerHoldsOnlyItsEnumeratedGrants() {
        assertThat(privileges("isp_worker")).containsExactlyInAnyOrder(
                "tenancy_tenant.id:SELECT",
                "tenancy_tenant.status:SELECT",
                "tenancy_tenant_data_key:SELECT",
                "network_router.id:SELECT",
                "network_router.tenant_id:SELECT",
                "network_router.name:SELECT",
                "network_router.tunnel_ip_a:SELECT",
                "network_router.rest_username:SELECT",
                "network_router.rest_password_enc:SELECT",
                "network_router.rest_key_version:SELECT",
                "network_router.status:SELECT",
                "network_router.poll_misses:SELECT",
                "network_router.offline_since:SELECT",
                "network_router.status:UPDATE",
                "network_router.poll_misses:UPDATE",
                "network_router.last_polled_at:UPDATE",
                "network_router.last_seen_at:UPDATE",
                "network_router.offline_since:UPDATE",
                "network_router.last_poll_error:UPDATE",
                "network_router.routeros_version:UPDATE",
                "network_router.board_name:UPDATE",
                "network_router.uptime_seconds:UPDATE",
                "network_router.cpu_load:UPDATE",
                "network_router.free_memory_bytes:UPDATE",
                "network_router.total_memory_bytes:UPDATE",
                "radius_client_v3.tenant_id:SELECT",
                "radius_client_v3.nasname:SELECT",
                "radius_client_v3.secret:SELECT",
                "outbox_event:INSERT",
                "shared_processed_event:INSERT");
    }

    @Test
    void N2_freeRadiusReadsTwoViewsAndWritesOnlyAccounting() {
        final List<String> radius = privileges("isp_radius");
        // The versioned views (the step-1 placeholders and older versions stay until a migration drops them).
        assertThat(radius).filteredOn(p -> !p.startsWith("radacct")).containsExactlyInAnyOrder(
                "radius_client:SELECT", "radius_client_v3:SELECT", "v_radius_auth:SELECT", "v_radius_auth_v3:SELECT",
                "v_radius_auth_v4:SELECT");
        assertThat(radius).filteredOn(p -> p.startsWith("radacct")).containsExactlyInAnyOrder(
                "radacct.acctsessionid:INSERT", "radacct.acctuniqueid:INSERT", "radacct.username:INSERT",
                "radacct.realm:INSERT", "radacct.nasipaddress:INSERT", "radacct.nasportid:INSERT",
                "radacct.nasporttype:INSERT", "radacct.acctstarttime:INSERT", "radacct.acctupdatetime:INSERT",
                "radacct.acctstoptime:INSERT", "radacct.acctsessiontime:INSERT", "radacct.acctauthentic:INSERT",
                "radacct.connectinfo_start:INSERT", "radacct.connectinfo_stop:INSERT", "radacct.acctinputoctets:INSERT",
                "radacct.acctoutputoctets:INSERT", "radacct.calledstationid:INSERT", "radacct.callingstationid:INSERT",
                "radacct.acctterminatecause:INSERT", "radacct.servicetype:INSERT", "radacct.framedprotocol:INSERT",
                "radacct.framedipaddress:INSERT",
                "radacct.acctuniqueid:SELECT", "radacct.acctstarttime:SELECT", "radacct.acctstoptime:SELECT",
                "radacct.acctterminatecause:SELECT", "radacct.nasipaddress:SELECT",
                "radacct.acctupdatetime:UPDATE", "radacct.acctstoptime:UPDATE", "radacct.acctsessiontime:UPDATE",
                "radacct.acctinputoctets:UPDATE", "radacct.acctoutputoctets:UPDATE", "radacct.acctterminatecause:UPDATE",
                "radacct.connectinfo_stop:UPDATE", "radacct.framedipaddress:UPDATE");
    }

    @Test
    void theApplicationRoleCannotRewriteHistoryOrReadRadiusSecrets() {
        final List<String> app = privileges("isp_app");
        assertThat(app).noneMatch(p -> p.matches("(identity_audit_log|identity_platform_audit_log|billing_ledger_entry).*:(UPDATE|DELETE)"));
        assertThat(app).noneMatch(p -> p.equals("network_radius_credential:SELECT") || p.equals("network_radius_credential.secret:SELECT"));
        assertThat(app).filteredOn(p -> p.startsWith("radacct")).containsExactlyInAnyOrder(
                "radacct:SELECT", "radacct.acctstoptime:UPDATE", "radacct.acctterminatecause:UPDATE");
        assertThat(app).contains("network_ensure_radacct_partitions():EXECUTE", "network_drop_radacct_partitions():EXECUTE")
                .doesNotContain("network_radacct_resolve():EXECUTE");
    }

    @Test
    void noSecurityDefinerFunctionIsExecutableByEveryone() {
        assertThat(OwnerSql.client().sql("""
                        select p.proname from pg_proc p
                        where p.pronamespace = 'public'::regnamespace and p.prosecdef
                          and (p.proacl is null or exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0))
                        """).query(String.class).list()).isEmpty();
    }

    private static List<String> privileges(final String role) {
        return OwnerSql.client().sql(PRIVILEGES).param("role", role).query(String.class).list();
    }
}
