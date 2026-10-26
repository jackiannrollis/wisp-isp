package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.network.domain.SecretGenerator;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.LineCredentialStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code network_radius_credential} (ADR 0009). The application role may insert
 * rows and update the secret but cannot select it, so this class can set a credential and never
 * read one back; FreeRADIUS reads them through the v3 views. New values are returned to the
 * caller once and never logged; every change writes an audit row without the value (A1, A3).
 */
@Component
public class RadiusCredentials implements LineCredentialStore {

    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;

    RadiusCredentials(final JdbcClient jdbc, final AuditTrail audit, final Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(final UUID subscriptionId) {
        final String password = SecretGenerator.pppoePassword();
        insert("subscription_id", subscriptionId, password);
        audit.record(AuditEntry.of("network.pppoe_password_issued", "subscription", subscriptionId));
        return password;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void keep(final UUID subscriptionId, final String password) {
        insert("subscription_id", subscriptionId, password);
        audit.record(AuditEntry.of("network.pppoe_password_kept", "subscription", subscriptionId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String regenerate(final UUID subscriptionId) {
        final String password = SecretGenerator.pppoePassword();
        update("subscription_id", subscriptionId, password);
        audit.record(AuditEntry.of("network.pppoe_password_regenerated", "subscription", subscriptionId));
        return password;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(final UUID subscriptionId) {
        update("subscription_id", subscriptionId, null);
        audit.record(AuditEntry.of("network.pppoe_password_revoked", "subscription", subscriptionId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String issueRouterSecret(final UUID routerId) {
        final String secret = SecretGenerator.sharedSecret();
        insert("router_id", routerId, secret);
        audit.record(AuditEntry.of("network.radius_secret_issued", "router", routerId));
        return secret;
    }

    /**
     * N10: a retired router's secret goes, so radius_client_v3 no longer lists it; the row stays (and its
     * updated_at moves, which is how the host's routersync knows to reload FreeRADIUS).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeRouterSecret(final UUID routerId) {
        update("router_id", routerId, null);
        audit.record(AuditEntry.of("network.radius_secret_revoked", "router", routerId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String regenerateRouterSecret(final UUID routerId) {
        final String secret = SecretGenerator.sharedSecret();
        update("router_id", routerId, secret);
        audit.record(AuditEntry.of("network.radius_secret_regenerated", "router", routerId));
        return secret;
    }

    private void insert(final String keyColumn, final UUID key, final String secret) {
        jdbc.sql(
                        "insert into network_radius_credential (id, tenant_id, " + keyColumn + ", secret, created_at, updated_at)"
                                + " values (:id, :tenantId, :key, :secret, :now, :now)")
                .param("id", UuidV7.next(clock))
                .param("tenantId", TenantContext.requireTenantId())
                .param("key", key)
                .param("secret", secret)
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private void update(final String keyColumn, final UUID key, final String secret) {
        final int updated =
                jdbc.sql(
                                "update network_radius_credential set secret = :secret, updated_at = :now"
                                        + " where " + keyColumn + " = :key")
                        .param("secret", secret)
                        .param("now", Timestamp.from(clock.instant()))
                        .param("key", key)
                        .update();
        if (updated != 1) {
            throw new IllegalStateException("no RADIUS credential row for " + keyColumn + " " + key);
        }
    }
}
