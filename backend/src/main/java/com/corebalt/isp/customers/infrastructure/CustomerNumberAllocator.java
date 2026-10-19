package com.corebalt.isp.customers.infrastructure;

import com.corebalt.isp.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Next customer number for the current tenant (T2): the tenant code plus a six-digit sequence. The
 * upsert row-locks the tenant's counter until the caller commits, so concurrent registrations
 * queue; a rolled-back registration leaves a gap, which is fine because numbers are never reused.
 */
@Component
public class CustomerNumberAllocator {

    private final JdbcClient jdbc;

    CustomerNumberAllocator(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(final String tenantCode) {
        final int sequence =
                jdbc.sql(
                                """
                                insert into customers_number_counter as c (tenant_id, last_value)
                                values (:tenantId, 1)
                                on conflict (tenant_id) do update set last_value = c.last_value + 1
                                returning c.last_value
                                """)
                        .param("tenantId", TenantContext.requireTenantId())
                        .query(Integer.class)
                        .single();
        return format(tenantCode, sequence);
    }

    static String format(final String tenantCode, final int sequence) {
        if (sequence < 1 || sequence > 999_999) {
            throw new IllegalStateException("T2: customer number sequence exhausted for " + tenantCode);
        }
        return tenantCode + String.format("%06d", sequence);
    }
}
