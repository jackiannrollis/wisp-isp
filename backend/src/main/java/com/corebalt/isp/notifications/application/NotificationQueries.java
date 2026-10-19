package com.corebalt.isp.notifications.application;

import com.corebalt.isp.shared.web.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What staff see: messages and their status, open SMS alerts, numbers the provider refused. */
@Service
public class NotificationQueries {

    public record MessageView(UUID id, UUID customerId, String recipient, String templateKey, String body, String status,
                              Instant scheduledFor, int attempts, String providerStatus, String failureReason,
                              Instant sentAt, Instant deliveredAt, Instant createdAt) {}

    public record AlertView(String kind, UUID subjectId, String detail, Instant raisedAt) {}

    public record PhoneFlagView(String phone, String status, String reason, Instant flaggedAt) {}

    private final JdbcClient jdbc;

    NotificationQueries(final JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    @Transactional(readOnly = true)
    public List<AlertView> openAlerts() {
        return jdbc.sql("select kind, subject_id, detail, raised_at from notifications_alert where cleared_at is null order by raised_at")
                .query((rs, n) -> new AlertView(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .list();
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
