package com.corebalt.isp.payments.application;

import com.corebalt.isp.payments.api.PaymentTotals;
import com.corebalt.isp.shared.money.Money;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sums in SQL over integer minor units (B2); row-level security keeps them to the current tenant. */
@Service
public class PaymentTotalsService implements PaymentTotals {

    private final JdbcClient jdbc;

    PaymentTotalsService(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Collected collected(final Instant from, final Instant to) {
        return jdbc.sql("""
                        select count(*),
                               coalesce(sum(amount_minor), 0),
                               count(*) filter (where status = 'UNMATCHED'),
                               coalesce(sum(amount_minor) filter (where status = 'UNMATCHED'), 0)
                        from payments_payment
                        where status <> 'REVERSED' and currency = 'KES' and received_at >= :from and received_at < :to
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new Collected(rs.getLong(1), Money.of(rs.getLong(2), Money.KES), rs.getLong(3),
                        Money.of(rs.getLong(4), Money.KES)))
                .single();
    }
}
