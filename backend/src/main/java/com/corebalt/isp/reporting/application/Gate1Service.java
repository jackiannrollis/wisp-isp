package com.corebalt.isp.reporting.application;

import com.corebalt.isp.network.api.NetworkStatistics;
import com.corebalt.isp.network.api.SessionReauthorized;
import com.corebalt.isp.notifications.api.AlertCounts;
import com.corebalt.isp.payments.api.CallbackPayments;
import com.corebalt.isp.reporting.api.Gate1Dto;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.subscriptions.api.CreditApplied;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gate 1 measured by the platform (docs/09, ADR 0018). Two facts are recorded from events: a callback
 * payment renewing a line that was cut off or throttled back to full speed ({@link CreditApplied}, by
 * kind: CUT_OFF, GRACE_THROTTLE, FAIR_USE; a first activation is not a restore), and a Disconnect or CoA
 * the router answered ({@link SessionReauthorized}). A restore is the platform's part done: at the
 * renewal when the line had no live session (no Disconnect is sent, its next login is FULL), otherwise
 * at the first of a reauthorization or a new session of the line within {@link #WINDOW} of the renewal.
 * {@link #completeOpenRestores} fills it, so it never matters which event arrived first. Everything runs
 * in the current tenant (T1).
 */
@Service
public class Gate1Service {

    /** How long after the renewal a restore may complete; later, it counts as missed. */
    static final Duration WINDOW = Duration.ofMinutes(10);
    /** docs/09: no restore slower than this after the callback. */
    static final long SLOW_SECONDS = 60;
    /** docs/09: payment matching above this share automatic, in percent. */
    static final long MATCHING_TARGET_PERCENT = 98;
    /** How far back open restores are completed: a job outage shorter than this loses nothing. */
    static final Duration LOOKBACK = Duration.ofDays(7);
    /** What a payment may lift, in the order screens list them. */
    public static final List<String> KINDS = List.of("CUT_OFF", "GRACE_THROTTLE", "FAIR_USE");
    private static final String TOTAL = "ALL";

    private final JdbcClient jdbc;
    private final CallbackPayments callbacks;
    private final NetworkStatistics network;
    private final AlertCounts alerts;
    private final Clock clock;

    Gate1Service(final JdbcClient jdbc, final CallbackPayments callbacks, final NetworkStatistics network,
                 final AlertCounts alerts, final Clock clock) {
        this.jdbc = jdbc;
        this.callbacks = callbacks;
        this.network = network;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** A payment that arrived by callback and lifted lines; manual payments and adjustments are not Gate 1. */
    @Transactional
    public void recordCredit(final CreditApplied event) {
        if (!"PAYMENT".equals(event.entryType()) || event.renewed() == null) {
            return;
        }
        final List<CreditApplied.RenewedLine> restored =
                event.renewed().stream().filter(line -> kindOf(line) != null).toList();
        if (restored.isEmpty()) {
            return;
        }
        final Optional<Instant> callbackAt = callbacks.firstCallbackAt(event.referenceId());
        if (callbackAt.isEmpty()) {
            return;
        }
        for (final CreditApplied.RenewedLine line : restored) {
            jdbc.sql("""
                            insert into reporting_restore (tenant_id, payment_id, subscription_id, callback_at, renewed_at, kind)
                            values (:tenantId, :paymentId, :subscriptionId, :callbackAt, :renewedAt, :kind)
                            on conflict do nothing
                            """)
                    .param("tenantId", TenantContext.requireTenantId())
                    .param("paymentId", event.referenceId())
                    .param("subscriptionId", line.subscriptionId())
                    .param("callbackAt", Timestamp.from(callbackAt.get()))
                    .param("renewedAt", Timestamp.from(event.occurredAt()))
                    .param("kind", kindOf(line))
                    .update();
        }
    }

    /**
     * What the renewal lifted: CUT_OFF for a RESTRICTED line (expired past grace, or a data cap), the
     * throttle's reason for a THROTTLED one; null for anything else, a first activation (REJECT) included,
     * and for a THROTTLED line in an event older than the reason it carries.
     */
    static String kindOf(final CreditApplied.RenewedLine line) {
        if ("RESTRICTED".equals(line.previousProfile())) {
            return "CUT_OFF";
        }
        if ("THROTTLED".equals(line.previousProfile())
                && ("GRACE_THROTTLE".equals(line.previousReason()) || "FAIR_USE".equals(line.previousReason()))) {
            return line.previousReason();
        }
        return null;
    }

    /**
     * A Disconnect or CoA the router took, or answered that the session had already ended: either way the
     * restricted session is gone and the redial is FULL.
     */
    @Transactional
    public void recordReauthorization(final SessionReauthorized event) {
        if (event.subscriptionId() == null) {
            return;
        }
        jdbc.sql("""
                        insert into reporting_reauthorization (tenant_id, event_id, subscription_id, occurred_at, session_gone)
                        values (:tenantId, :eventId, :subscriptionId, :occurredAt, :sessionGone)
                        on conflict do nothing
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("eventId", event.eventId())
                .param("subscriptionId", event.subscriptionId())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("sessionGone", event.sessionAlreadyGone())
                .update();
    }

    /**
     * Completes the open restores of the last {@link #LOOKBACK}: at the renewal when the line had no live
     * session then, otherwise at the earliest of a reauthorization or a new session within {@link #WINDOW}
     * of the renewal. Returns how many completed.
     */
    @Transactional
    public int completeOpenRestores() {
        final Instant now = clock.instant();
        final List<Open> open = jdbc.sql("""
                        select payment_id, subscription_id, renewed_at from reporting_restore
                        where restored_at is null and renewed_at >= :since
                        """)
                .param("since", Timestamp.from(now.minus(LOOKBACK)))
                .query((rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant()))
                .list();
        int completed = 0;
        for (final Open restore : open) {
            final Optional<Done> done = done(restore);
            if (done.isEmpty()) {
                continue;
            }
            completed += jdbc.sql("""
                            update reporting_restore set restored_at = :at, restored_by = :by
                            where payment_id = :paymentId and subscription_id = :subscriptionId and restored_at is null
                            """)
                    .param("at", Timestamp.from(done.get().at()))
                    .param("by", done.get().by())
                    .param("paymentId", restore.paymentId())
                    .param("subscriptionId", restore.subscriptionId())
                    .update();
        }
        return completed;
    }

    /** Gate 1 over [from, to), restores completed first so the answer is current. */
    @Transactional
    public Gate1Dto gate1(final Instant from, final Instant to) {
        completeOpenRestores();
        final Instant now = clock.instant();
        final Map<String, Gate1Dto.KindRestores> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        select kind,
                               count(*),
                               count(*) filter (where restored_at is not null),
                               count(*) filter (where restored_at is null and renewed_at >= :pendingSince),
                               count(*) filter (where restored_at is null and renewed_at < :pendingSince),
                               count(*) filter (where extract(epoch from restored_at - callback_at) > :slow),
                               ceil(percentile_cont(0.95) within group (order by extract(epoch from restored_at - callback_at)))::bigint,
                               ceil(max(extract(epoch from restored_at - callback_at)))::bigint
                        from reporting_restore
                        where renewed_at >= :from and renewed_at < :to
                        group by grouping sets ((kind), ())
                        """)
                .param("pendingSince", Timestamp.from(now.minus(WINDOW)))
                .param("slow", SLOW_SECONDS)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(rs -> {
                    final String kind = rs.getString(1);
                    rows.put(kind == null ? TOTAL : kind, new Gate1Dto.KindRestores(kind == null ? TOTAL : kind,
                            rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                            rs.getObject(7, Long.class), rs.getObject(8, Long.class)));
                });
        final Gate1Dto.KindRestores all = rows.getOrDefault(TOTAL, empty(TOTAL));
        final Gate1Dto.Restores restores = new Gate1Dto.Restores(all.total(), all.completed(), all.pending(), all.missed(),
                all.slowerThan60s(), all.p95Seconds(), all.maxSeconds());
        final List<Gate1Dto.KindRestores> byKind = KINDS.stream().map(k -> rows.getOrDefault(k, empty(k))).toList();

        final Map<String, Long> manual = new LinkedHashMap<>();
        jdbc.sql("""
                        select kind, count(*) from reporting_manual_reconnect
                        where occurred_at >= :from and occurred_at < :to group by kind
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(rs -> {
                    manual.put(rs.getString(1), rs.getLong(2));
                });
        final CallbackPayments.Matching matched = callbacks.matching(from, to);
        // For display only, to one decimal; the target is judged on the counts.
        final Double percent = matched.total() == 0 ? null
                : Math.round(1000.0 * matched.automatic() / matched.total()) / 10.0;
        final AlertCounts.Raised raised = alerts.raised(from, to);
        final Gate1Dto.Reconnects reconnects = new Gate1Dto.Reconnects(manual.getOrDefault("STAFF_DISCONNECT", 0L),
                manual.getOrDefault("PAID_OFFLINE", 0L), restores.missed(), raised.coaFailed(), raised.routerOffline());
        final long handsNeeded = reconnects.staffDisconnects() + reconnects.paidOfflineReports() + reconnects.missedRestores()
                + reconnects.coaFailedAlerts() + reconnects.routerOfflineAlerts();
        final Gate1Dto.Targets targets = new Gate1Dto.Targets(
                matchingAboveTarget(matched.automatic(), matched.total()),
                restores.total() == restores.pending() ? null : restores.slowerThan60s() == 0 && restores.missed() == 0,
                // A period with no payment, restore, report or alert has nothing to judge.
                matched.total() == 0 && restores.total() == 0 && handsNeeded == 0 ? null : handsNeeded == 0);
        return new Gate1Dto(from, to,
                new Gate1Dto.Matching(matched.automatic(), matched.byStaff(), matched.unmatched(), percent),
                restores, byKind, reconnects, targets);
    }

    private static Gate1Dto.KindRestores empty(final String kind) {
        return new Gate1Dto.KindRestores(kind, 0, 0, 0, 0, 0, null, null);
    }

    /**
     * docs/09: above 98% automatic, judged on the counts so that 9,804 of 10,000 passes although it shows as
     * 98.0%; null when no payment arrived by callback.
     */
    static Boolean matchingAboveTarget(final long automatic, final long total) {
        return total == 0 ? null : automatic * 100 > total * MATCHING_TARGET_PERCENT;
    }

    private Optional<Done> done(final Open restore) {
        if (!network.sessionOpenAt(restore.subscriptionId(), restore.renewedAt())) {
            return Optional.of(new Done(restore.renewedAt(), "NO_SESSION"));
        }
        final Instant until = restore.renewedAt().plus(WINDOW);
        final Optional<Done> reauthorized = jdbc.sql("""
                        select occurred_at, session_gone from reporting_reauthorization
                        where subscription_id = :id and occurred_at >= :from and occurred_at <= :to
                        order by occurred_at limit 1
                        """)
                .param("id", restore.subscriptionId())
                .param("from", Timestamp.from(restore.renewedAt()))
                .param("to", Timestamp.from(until))
                .query((rs, n) -> new Done(rs.getTimestamp(1).toInstant(), rs.getBoolean(2) ? "SESSION_GONE" : "REAUTHORIZED"))
                .optional();
        final Optional<Done> session = network.firstSessionStart(restore.subscriptionId(), restore.renewedAt(), until)
                .map(at -> new Done(at, "NEW_SESSION"));
        if (reauthorized.isEmpty()) {
            return session;
        }
        if (session.isEmpty() || !session.get().at().isBefore(reauthorized.get().at())) {
            return reauthorized;
        }
        return session;
    }

    private record Open(UUID paymentId, UUID subscriptionId, Instant renewedAt) {}

    private record Done(Instant at, String by) {}
}
