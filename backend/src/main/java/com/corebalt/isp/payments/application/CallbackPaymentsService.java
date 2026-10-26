package com.corebalt.isp.payments.application;

import com.corebalt.isp.payments.api.CallbackPayments;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Callback payments for Gate 1 (ADR 0018). A payment arrived by callback when a stored callback points at
 * it (payments_mpesa_callback.payment_id, P2); an STK payment whose C2B confirmation also came is linked
 * twice, and the first arrival counts. Row-level security keeps both tables to the current tenant.
 */
@Service
class CallbackPaymentsService implements CallbackPayments {

    private final JdbcClient jdbc;

    CallbackPaymentsService(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> firstCallbackAt(final UUID paymentId) {
        return jdbc.sql("select min(received_at) from payments_mpesa_callback where payment_id = :id")
                .param("id", paymentId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    @Override
    @Transactional(readOnly = true)
    public Matching matching(final Instant from, final Instant to) {
        return jdbc.sql("""
                        select count(*) filter (where p.status = 'MATCHED' and p.matched_by is null),
                               count(*) filter (where p.status = 'MATCHED' and p.matched_by is not null),
                               count(*) filter (where p.status = 'UNMATCHED')
                        from payments_payment p
                        where p.created_at >= :from and p.created_at < :to
                          and exists (select 1 from payments_mpesa_callback c where c.payment_id = p.id)
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new Matching(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
    }
}
