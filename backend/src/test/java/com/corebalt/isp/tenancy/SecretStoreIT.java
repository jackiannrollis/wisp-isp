package com.corebalt.isp.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.support.Api;
import com.corebalt.isp.support.IntegrationTest;
import com.corebalt.isp.support.OwnerSql;
import com.corebalt.isp.support.TestTenants;
import com.corebalt.isp.tenancy.api.SecretStore;
import com.corebalt.isp.tenancy.api.SecretStore.SecretKind;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Envelope-encrypted, write-only tenant secrets (T4, docs/07). Values are obvious fakes. */
class SecretStoreIT extends IntegrationTest {

    static final String FAKE = "fake-consumer-secret-for-tests";

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired SecretStore secrets;

    UUID tenant;
    Api api;

    @BeforeEach
    void tenant() {
        tenant = TestTenants.create(jdbc, tx);
        api = new Api(port, tenant);
    }

    @Test
    void T4_secretsAreWriteOnlyThroughTheApi() {
        assertThat(api.put("/api/v1/settings/secrets/MPESA_CONSUMER_SECRET", "{\"value\":\"" + FAKE + "\"}").status())
                .isEqualTo(204);
        final Api.Response listed = api.get("/api/v1/settings/secrets");
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).contains("MPESA_CONSUMER_SECRET").doesNotContain(FAKE);
        assertThat(TenantContext.callAsTenant(tenant, () -> secrets.reveal(SecretKind.MPESA_CONSUMER_SECRET)))
                .contains(FAKE);
        assertThat(new Api(port, tenant, "billing.adjust").get("/api/v1/settings/secrets").status()).isEqualTo(403);
    }

    @Test
    void T4_ciphertextIsNotThePlaintextAndDiffersPerTenant() {
        final UUID other = TestTenants.create(jdbc, tx);
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> secrets.put(SecretKind.MPESA_PASSKEY, FAKE)));
        TenantContext.runAsTenant(other, () -> tx.executeWithoutResult(s -> secrets.put(SecretKind.MPESA_PASSKEY, FAKE)));
        final var rows = OwnerSql.client()
                .sql("select ciphertext from tenancy_tenant_secret where kind = 'MPESA_PASSKEY' and tenant_id in (:a, :b)")
                .param("a", tenant).param("b", other)
                .query(byte[].class)
                .list();
        assertThat(rows).hasSize(2);
        assertThat(new String(rows.get(0), StandardCharsets.ISO_8859_1)).doesNotContain(FAKE);
        assertThat(rows.get(0)).isNotEqualTo(rows.get(1));
        assertThat(OwnerSql.client()
                        .sql("select count(*) from identity_audit_log where action = 'tenancy.secret_replaced' and tenant_id = :t and after_state::text not like :fake")
                        .param("t", tenant).param("fake", "%" + FAKE + "%")
                        .query(Long.class).single())
                .isOne();
    }

    @Test
    void T1_aTenantCannotRevealAnotherTenantsSecret() {
        TenantContext.runAsTenant(tenant, () -> tx.executeWithoutResult(s -> secrets.put(SecretKind.MPESA_CONSUMER_KEY, FAKE)));
        final UUID other = TestTenants.create(jdbc, tx);
        assertThat(TenantContext.callAsTenant(other, () -> secrets.reveal(SecretKind.MPESA_CONSUMER_KEY))).isEmpty();
    }
}
