package com.corebalt.isp.support;

import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates tenant registry rows for tests (the registry is platform-scoped, no RLS). */
public final class TestTenants {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TestTenants() {}

    public static UUID create(final JdbcClient jdbc, final TransactionTemplate tx) {
        final UUID id = UuidV7.next();
        final String code = code(SEQUENCE.incrementAndGet());
        TenantContext.callAsPlatform(
                "test-setup",
                () ->
                        tx.execute(
                                status ->
                                        jdbc.sql(
                                                        """
                                                        insert into tenancy_tenant (id, code, name, subdomain, status)
                                                        values (:id, :code, :name, :subdomain, 'ACTIVE')
                                                        """)
                                                .param("id", id)
                                                .param("code", code)
                                                .param("name", "Tenant " + code)
                                                .param("subdomain", code.toLowerCase())
                                                .update()));
        return id;
    }

    /** Two to four upper-case letters, unique per test JVM. */
    private static String code(final int n) {
        final StringBuilder code = new StringBuilder();
        int value = n + 26; // start at "BA" so every code has at least two letters
        while (value > 0) {
            code.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return code.toString();
    }
}
