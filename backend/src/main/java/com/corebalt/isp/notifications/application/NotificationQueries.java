package com.corebalt.isp.notifications.application;

import com.corebalt.isp.network.api.RouterNames;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What staff see: messages and their status, staff alerts, numbers the provider refused. */
@Service
public class NotificationQueries {

    public record MessageView(UUID id, UUID customerId, String recipient, String templateKey, String body, String status,
                              Instant scheduledFor, int attempts, String providerStatus, String failureReason,
                              Instant sentAt, Instant deliveredAt, Instant createdAt) {}

    /**
     * A staff alert. {@code subjectLabel} names what it is about (the router's name for ROUTER_OFFLINE, the
     * PPPoE username for COA_FAILED) and {@code customerId} is the line's customer, so the screen can link
     * to it; both are null for tenant-wide SMS alerts or a subject that no longer exists.
     */
    public record AlertView(UUID id, String kind, UUID subjectId, String subjectLabel, UUID customerId, String detail,
                            Instant raisedAt, Instant clearedAt) {}

    public record PhoneFlagView(String phone, String status, String reason, Instant flaggedAt) {}

    /** Alert filter: still open, or cleared (history). */
    public static final Set<String> ALERT_STATES = Set.of("OPEN", "CLEARED");

    private final JdbcClient jdbc;
    private final RouterNames routers;
    private final SubscriptionDirectory lines;

    NotificationQueries(final JdbcClient jdbc, final RouterNames routers, final SubscriptionDirectory lines) {
        this.jdbc = jdbc;
        this.routers = routers;
        this.lines = lines;
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageView> messages(final UUID customerId, final String status, final Pageable page) {
        final String where = " where (cast(:customerId as uuid) is null or customer_id = cast(:customerId as uuid))"
                + " and (cast(:status as text) is null or status = cast(:status as text))";
        final List<MessageView> items = jdbc.sql("""
                        select id, customer_id, recipient, template_key, body, status, scheduled_for, attempts, provider_status,
                               failure_reason, sent_at, delivered_at, created_at
                        from notifications_notification""" + where + " order by created_at desc, id desc limit :limit offset :offset")
                .param("customerId", customerId)
                .param("status", status)
                .param("limit", page.getPageSize())
                .param("offset", page.getOffset())
                .query((rs, n) -> new MessageView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getInt(8),
                        rs.getString(9), rs.getString(10), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
                        rs.getTimestamp(13).toInstant()))
                .list();
        final long total = jdbc.sql("select count(*) from notifications_notification" + where)
                .param("customerId", customerId).param("status", status).query(Long.class).single();
        return PageResponse.of(new PageImpl<>(items, page, total), m -> m);
    }

    /** Open alerts oldest problem first; cleared ones most recently cleared first. */
    @Transactional(readOnly = true)
    public PageResponse<AlertView> alerts(final String state, final Pageable page) {
        if (!ALERT_STATES.contains(state)) {
            throw InvalidRequestException.of("state", "must be OPEN or CLEARED");
        }
        final boolean open = state.equals("OPEN");
        final String where = open ? " where cleared_at is null" : " where cleared_at is not null";
        final String order = open ? " order by raised_at, id" : " order by cleared_at desc, id desc";
        final Map<String, Subject> subjects = new HashMap<>();
        final List<AlertView> items = jdbc.sql("select id, kind, subject_id, detail, raised_at, cleared_at from notifications_alert"
                        + where + order + " limit :limit offset :offset")
                .param("limit", page.getPageSize())
                .param("offset", page.getOffset())
                .query((rs, n) -> {
                    final String kind = rs.getString(2);
                    final UUID subjectId = rs.getObject(3, UUID.class);
                    final Subject subject = subjectId == null
                            ? Subject.NONE
                            : subjects.computeIfAbsent(kind + subjectId, k -> subject(kind, subjectId));
                    return new AlertView(rs.getObject(1, UUID.class), kind, subjectId, subject.label(), subject.customerId(),
                            rs.getString(4), rs.getTimestamp(5).toInstant(), instant(rs.getTimestamp(6)));
                })
                .list();
        final long total = jdbc.sql("select count(*) from notifications_alert" + where).query(Long.class).single();
        return PageResponse.of(new PageImpl<>(items, page, total), a -> a);
    }

    private Subject subject(final String kind, final UUID subjectId) {
        return switch (kind) {
            case "ROUTER_OFFLINE" -> routers.name(subjectId).map(name -> new Subject(name, null)).orElse(Subject.NONE);
            case "COA_FAILED" -> lines.line(subjectId)
                    .map(line -> new Subject(line.pppoeUsername(), line.customerId()))
                    .orElse(Subject.NONE);
            default -> Subject.NONE;
        };
    }

    private record Subject(String label, UUID customerId) {
        static final Subject NONE = new Subject(null, null);
    }

    @Transactional(readOnly = true)
    public List<PhoneFlagView> phoneFlags() {
        return jdbc.sql("select phone, status, reason, flagged_at from notifications_phone_flag order by flagged_at desc")
                .query((rs, n) -> new PhoneFlagView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
                .list();
    }

    /** Staff corrected the number (or the customer left the blacklist): messages flow again. */
    @Transactional
    public void clearPhoneFlag(final String phone) {
        jdbc.sql("delete from notifications_phone_flag where phone = :phone").param("phone", phone).update();
    }

    private static Instant instant(final java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
