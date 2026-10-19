package com.corebalt.isp.payments.application;

import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.tenant.TenantIterator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Callback intake (docs/05 Callback handling 2 and 7, P2): the raw body is stored and committed
 * first, the caller acknowledges Daraja, and processing runs afterwards on another thread, which
 * must set the tenant context itself. A sweeper retries anything stored but not processed (a crash
 * between store and process), from the stored copy, up to {@link #MAX_ATTEMPTS}.
 */
@Service
public class CallbackInbox {

    private static final Logger log = LoggerFactory.getLogger(CallbackInbox.class);

    public static final int MAX_ATTEMPTS = 10;

    public enum Kind {
        C2B_VALIDATION,
        C2B_CONFIRMATION,
        STK_RESULT,
        TXN_STATUS_RESULT,
        TXN_STATUS_TIMEOUT
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CallbackProcessor processor;
    private final Executor executor;
    private final TenantIterator tenants;
    private final Clock clock;

    CallbackInbox(
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final CallbackProcessor processor,
            @Qualifier("applicationTaskExecutor") final Executor executor,
            final TenantIterator tenants,
            final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.processor = processor;
        this.executor = executor;
        this.tenants = tenants;
        this.clock = clock;
    }

    /** Stores the body in its own committed transaction and schedules processing; returns the row ID. */
    public UUID receive(final UUID tenantId, final Kind kind, final String rawBody, final String sourceIp) {
        final UUID id = UuidV7.next(clock);
        TenantContext.runAsTenant(tenantId, () -> tx.executeWithoutResult(s -> jdbc.sql("""
                        insert into payments_mpesa_callback (id, tenant_id, kind, raw_body, source_ip, received_at)
                        values (:id, :tenantId, :kind, :body, :ip, :now)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("kind", kind.name())
                .param("body", rawBody == null ? "" : rawBody)
                .param("ip", sourceIp)
                .param("now", Timestamp.from(clock.instant()))
                .update()));
        executor.execute(() -> processQuietly(tenantId, id));
        return id;
    }

    /** Retries stored callbacks not processed after 30 seconds (P2). */
    @Scheduled(fixedDelayString = "${isp.mpesa.callbacks.sweep-interval:30s}", initialDelayString = "${isp.mpesa.callbacks.sweep-initial-delay:30s}")
    public void sweep() {
        tenants.forEachTenant("mpesa-callback-sweep", tenantId -> {
            final List<UUID> pending = tx.execute(s -> jdbc.sql("""
                            select id from payments_mpesa_callback
                            where processed_at is null and attempts < :max and received_at < :cutoff
                            order by received_at limit 200
                            """)
                    .param("max", MAX_ATTEMPTS)
                    .param("cutoff", Timestamp.from(clock.instant().minus(Duration.ofSeconds(30))))
                    .query(UUID.class)
                    .list());
            Optional.ofNullable(pending).orElse(List.of()).forEach(id -> processQuietly(tenantId, id));
        });
    }

    private void processQuietly(final UUID tenantId, final UUID callbackId) {
        try {
            try {
                TenantContext.runAsTenant(tenantId, () -> processor.process(callbackId));
            } catch (DataIntegrityViolationException e) {
                // The same TransID arrived twice at once and the other copy committed first; processed
                // again now, this copy is found to be a duplicate and recorded without side effects (P1).
                TenantContext.runAsTenant(tenantId, () -> processor.process(callbackId));
            }
        } catch (RuntimeException e) {
            // Never log the body: it carries payer data (A3).
            log.warn("M-Pesa callback {} failed: {}", callbackId, e.getClass().getSimpleName());
            TenantContext.runAsTenant(tenantId, () -> tx.executeWithoutResult(s -> jdbc.sql("""
                            update payments_mpesa_callback
                            set attempts = attempts + 1, last_error = left(:error, 500)
                            where id = :id and processed_at is null
                            """)
                    .param("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .param("id", callbackId)
                    .update()));
        }
    }
}
