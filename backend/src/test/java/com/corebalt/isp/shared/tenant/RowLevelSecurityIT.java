package com.corebalt.isp.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs as {@code isp_app} against real PostgreSQL: RLS and grants, not just code, enforce T1. */
class RowLevelSecurityIT extends IntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    UUID tenantA;
    UUID tenantB;

    @BeforeEach
    void tenants() {
        tenantA = TestTenants.create(jdbc, tx);
        tenantB = TestTenants.create(jdbc, tx);
    }

    @Test
    void applicationRoleOwnsNothingSoRlsApplies() {
        final Boolean bypass =
                TenantContext.callAsPlatform(
                        "test",
                        () ->
                                tx.execute(
                                        s ->
                                                jdbc.sql(
                                                                "select rolsuper or rolbypassrls from pg_roles where rolname = current_user")
                                                        .query(Boolean.class)
                                                        .single()));
        assertThat(bypass).isFalse();
        final String owner =
                TenantContext.callAsPlatform(
                        "test",
                        () ->
                                tx.execute(
                                        s ->
                                                jdbc.sql(
                                                                "select tableowner from pg_tables where tablename = 'identity_audit_log'")
                                                        .query(String.class)
                                                        .single()));
        assertThat(owner).isNotEqualTo("isp_app");
    }

    @Test
    void T1_transactionWithoutTenantContextFails() {
        assertThatThrownBy(() -> tx.execute(s -> jdbc.sql("select 1").query(Integer.class).single()))
                .isInstanceOf(TenantContextMissingException.class)
                .hasMessageStartingWith("T1");
    }

    @Test
    void T1_tenantSeesOnlyItsOwnRows() {
        insertAudit(tenantA);
        insertAudit(tenantA);
        insertAudit(tenantB);

        assertThat(countAudit(tenantA)).isEqualTo(2);
        assertThat(countAudit(tenantB)).isEqualTo(1);
    }

    @Test
    void T1_platformScopeSeesNoTenantRows() {
        insertAudit(tenantA);
        final Long visible =
                TenantContext.callAsPlatform(
                        "test",
                        () ->
                                tx.execute(
                                        s ->
                                                jdbc.sql("select count(*) from identity_audit_log")
                                                        .query(Long.class)
                                                        .single()));
        assertThat(visible).isZero();
    }

    @Test
    void T1_cannotWriteRowsForAnotherTenant() {
        assertThatThrownBy(
                        () ->
                                TenantContext.runAsTenant(
                                        tenantA,
                                        () ->
                                                tx.executeWithoutResult(
                                                        s -> insertAuditRow(tenantB))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void A1_auditLogIsAppendOnlyForTheApplicationRole() {
        insertAudit(tenantA);
        assertThatThrownBy(() -> inTenant(tenantA, "update identity_audit_log set reason = 'x'"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> inTenant(tenantA, "delete from identity_audit_log"))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void E1_outboxAllowsOnlyRelayColumnUpdatesAndNoDelete() {
        final UUID eventId = UuidV7.next();
        TenantContext.runAsTenant(
                tenantA,
                () ->
                        tx.executeWithoutResult(
                                s ->
                                        jdbc.sql(
                                                        """
                                                        insert into outbox_event (id, tenant_id, module, event_type, version,
                                                                                  routing_key, payload, occurred_at)
                                                        values (:id, :tenant, 'tenancy', 'X', 1, 'tenancy.X.v1', '{}', now())
                                                        """)
                                                .param("id", eventId)
                                                .param("tenant", tenantA)
                                                .update()));

        inTenant(tenantA, "update outbox_event set published_at = now(), attempts = 1, last_error = null");
        assertThatThrownBy(() -> inTenant(tenantA, "update outbox_event set payload = '{\"a\":1}'"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> inTenant(tenantA, "delete from outbox_event"))
                .rootCause().hasMessageContaining("permission denied");
    }

    private void inTenant(final UUID tenant, final String sql) {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> jdbc.sql(sql).update()));
    }

    private void insertAudit(final UUID tenant) {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> insertAuditRow(tenant)));
    }

    private void insertAuditRow(final UUID tenant) {
        jdbc.sql(
                        """
                        insert into identity_audit_log (id, tenant_id, actor_type, action, target_type, occurred_at)
                        values (:id, :tenant, 'SYSTEM', 'TEST', 'TEST', now())
                        """)
                .param("id", UuidV7.next())
                .param("tenant", tenant)
                .update();
    }

    private long countAudit(final UUID tenant) {
        return TenantContext.callAsTenant(
                tenant,
                () ->
                        tx.execute(
                                s ->
                                        jdbc.sql("select count(*) from identity_audit_log")
                                                .query(Long.class)
                                                .single()));
    }
}
