package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Row-level security limits every query to the current tenant (T1). */
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID>, JpaSpecificationExecutor<Subscription> {

    long countByCustomerId(UUID customerId);

    Page<Subscription> findByCustomerId(UUID customerId, Pageable page);

    long countByRouterIdAndStateNot(UUID routerId, SubscriptionState state);

    /** N10: a retired router's lines that are not TERMINATED move to its replacement (bookkeeping, T3). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Subscription s set s.routerId = :to, s.updatedAt = :now where s.routerId = :from "
            + "and s.state <> com.corebalt.isp.subscriptions.domain.SubscriptionState.TERMINATED")
    int moveLiveLines(UUID from, UUID to, Instant now);

    /** Which of these usernames a line of the current tenant already has (the import's kept names, T2). */
    @Query("select s.pppoeUsername from Subscription s where s.pppoeUsername in :usernames")
    List<String> findPppoeUsernamesIn(Collection<String> usernames);

    /** Row lock for transitions and renewals, so two cannot interleave on one line. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = :id")
    Optional<Subscription> findForUpdate(UUID id);

    /** PENDING and ACTIVE lines of a customer, PENDING first, then earliest expiry first. */
    @Query("select s from Subscription s where s.customerId = :customerId and s.state in "
            + "(com.corebalt.isp.subscriptions.domain.SubscriptionState.PENDING, "
            + "com.corebalt.isp.subscriptions.domain.SubscriptionState.ACTIVE) "
            + "order by s.expiresAt asc nulls first, s.createdAt asc")
    List<Subscription> findRenewableByCustomer(UUID customerId);

    /** ACTIVE lines expiring before {@code horizon} (expired ones included), earliest first. */
    @Query("select s from Subscription s where s.state = com.corebalt.isp.subscriptions.domain.SubscriptionState.ACTIVE "
            + "and s.expiresAt < :horizon order by s.customerId, s.expiresAt")
    List<Subscription> findActiveExpiringBefore(Instant horizon);

    @Query("select s.id from Subscription s where s.state = :state and s.pauseUntil <= :now order by s.pauseUntil")
    List<UUID> findIdsPausedUntilBefore(SubscriptionState state, Instant now);

    /**
     * ACTIVE lines a reminder could be due for: expiring within four days, or not more than a day
     * past the end of their grace (ReminderSchedule).
     */
    @Query(
            value =
                    """
                    select * from subscriptions_subscription s
                    where s.state = 'ACTIVE'
                      and s.expires_at < cast(:now as timestamptz) + interval '4 days'
                      and s.expires_at + make_interval(days => s.grace_days) + interval '1 day' > cast(:now as timestamptz)
                    """,
            nativeQuery = true)
    List<Subscription> findActiveInReminderRange(Instant now);
}
