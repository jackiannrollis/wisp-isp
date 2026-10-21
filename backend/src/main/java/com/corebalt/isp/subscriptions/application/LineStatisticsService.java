package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.billing.api.Wallets;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageSpeeds;
import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.subscriptions.api.LineStatistics;
import com.corebalt.isp.subscriptions.api.PortalLineDto;
import com.corebalt.isp.subscriptions.api.PortalLinesDto;
import com.corebalt.isp.subscriptions.api.SubscriptionDto;
import com.corebalt.isp.subscriptions.domain.AccessPolicy;
import com.corebalt.isp.subscriptions.domain.DisplayStatus;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models over lines: the dashboard's counts and renewals due, and the portal's view of one
 * customer's lines. Profiles and display statuses are derived at read time and never stored (S1): by
 * {@link AccessPolicy} and {@link DisplayStatus} for the portal, and by their SQL copy
 * subscription_line_access() for the counts (S3).
 */
@Service
public class LineStatisticsService implements LineStatistics {

    private final JdbcClient jdbc;
    private final SubscriptionRepository lines;
    private final SubscriptionViews views;
    private final PackageCatalog packages;
    private final Wallets wallets;

    LineStatisticsService(final JdbcClient jdbc, final SubscriptionRepository lines, final SubscriptionViews views,
                          final PackageCatalog packages, final Wallets wallets) {
        this.jdbc = jdbc;
        this.lines = lines;
        this.views = views;
        this.packages = packages;
        this.wallets = wallets;
    }

    @Override
    @Transactional(readOnly = true)
    public LineCounts counts(final Instant now) {
        final Map<String, Long> byState = zeros(SubscriptionState.values());
        final Map<String, Long> byDisplay = zeros(DisplayStatus.values());
        final long[] total = {0};
        // subscription_line_access() is the SQL copy of AccessPolicy and DisplayStatus, with each line's
        // cycle usage (S3), judged at the same instant for every line.
        jdbc.sql("""
                        select s.state, a.display_status, count(*)
                        from subscriptions_subscription s
                        cross join lateral subscription_line_access(s.id, :now) a
                        group by 1, 2
                        """)
                .param("now", Timestamp.from(now))
                .query(rs -> {
                    byState.merge(rs.getString(1), rs.getLong(3), Long::sum);
                    byDisplay.merge(rs.getString(2), rs.getLong(3), Long::sum);
                    total[0] += rs.getLong(3);
                });
        return new LineCounts(total[0], byState, byDisplay);
    }

    @Override
    @Transactional(readOnly = true)
    public RenewalsDue renewalsDue(final Instant from, final Instant to) {
        final List<Due> due = due("expires_at >= :from and expires_at < :to", from, to);
        final Map<UUID, Money> prices = new HashMap<>();
        final Map<UUID, Money> balances = new HashMap<>();
        Money amount = Money.zero(Money.KES);
        Money outstanding = Money.zero(Money.KES);
        for (final Due line : due) {
            final Money price = price(prices, line.packageId());
            final Money shortfall = price.minus(balances.computeIfAbsent(line.customerId(), c -> wallets.balance(c, Money.KES)));
            amount = amount.plus(price);
            outstanding = shortfall.isPositive() ? outstanding.plus(shortfall) : outstanding;
        }
        return new RenewalsDue(due.size(), amount, outstanding);
    }

    @Override
    @Transactional(readOnly = true)
    public Lapsed lapsed(final Instant before) {
        final List<Due> lapsed = due("expires_at < :from", before, before);
        final Map<UUID, Money> prices = new HashMap<>();
        Money amount = Money.zero(Money.KES);
        for (final Due line : lapsed) {
            amount = amount.plus(price(prices, line.packageId()));
        }
        return new Lapsed(lapsed.size(), amount);
    }

    private record Due(UUID customerId, UUID packageId) {}

    /** ACTIVE lines matching {@code expiryCondition}, with the package their next renewal sells (S10). */
    private List<Due> due(final String expiryCondition, final Instant from, final Instant to) {
        return jdbc.sql("select customer_id, coalesce(pending_package_id, package_id) from subscriptions_subscription "
                        + "where state = 'ACTIVE' and " + expiryCondition)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .list();
    }

    private Money price(final Map<UUID, Money> prices, final UUID packageId) {
        return prices.computeIfAbsent(packageId,
                id -> packages.terms(id).map(PackageTerms::price).orElse(Money.zero(Money.KES)));
    }

    /** Display statuses whose line needs paying for now: not yet active, about to expire, or expired. */
    static final Set<String> DUE = Set.of("PENDING", "EXPIRING", "GRACE", "RESTRICTED");

    /** Limited by usage (N9): offered early renewal, and due like any line once its expiry is near. */
    static final Set<String> USAGE_LIMITED = Set.of("THROTTLED", "CAPPED");

    /**
     * The customer's lines, earliest opened first (terminated lines left out), the wallet balance, and
     * "pay all": what the wallet lacks to renew every due line at once, max(0, sum of their renewal
     * prices - balance). Per-line Pay Now uses each line's own shortfall.
     */
    @Transactional(readOnly = true)
    public PortalLinesDto portalLines(final UUID customerId) {
        final Money balance = wallets.balance(customerId, Money.KES);
        final List<Subscription> open = lines.findByCustomerId(customerId, Pageable.unpaged(Sort.by("createdAt"))).stream()
                .filter(line -> line.getState() != SubscriptionState.TERMINATED)
                .toList();
        final SubscriptionViews.Mapper mapper = views.mapper(open);
        final List<PortalLineDto> mine = open.stream()
                .map(line -> portalLine(line, mapper.toDto(line), balance, mapper.now()))
                .toList();
        Money dueTotal = Money.zero(Money.KES);
        for (final PortalLineDto line : mine) {
            dueTotal = line.due() || line.earlyRenewal() ? dueTotal.plus(line.renewalPrice().toMoney()) : dueTotal;
        }
        final Money payAll = dueTotal.minus(balance);
        return new PortalLinesDto(mine, MoneyDto.of(balance), MoneyDto.of(payAll.isPositive() ? payAll : Money.zero(Money.KES)));
    }

    private PortalLineDto portalLine(final Subscription line, final SubscriptionDto view, final Money balance,
                                     final Instant now) {
        final PackageTerms current = packages.terms(line.getPackageId()).orElseThrow();
        final PackageSpeeds speeds = packages.speeds(line.getPackageId()).orElseThrow();
        final PackageTerms next = packages.terms(line.packageForNextRenewal()).orElse(current);
        final Money shortfall = next.price().minus(balance);
        final boolean usageLimited = USAGE_LIMITED.contains(view.displayStatus());
        final boolean nearExpiry = line.getExpiresAt() != null
                && !now.isBefore(line.getExpiresAt().minus(DisplayStatus.EXPIRING_WINDOW));
        final boolean due = DUE.contains(view.displayStatus()) || (usageLimited && nearExpiry);
        return new PortalLineDto(
                line.getId(),
                line.getPppoeUsername(),
                current.name(),
                speeds.downloadKbps(),
                speeds.uploadKbps(),
                next.name(),
                MoneyDto.of(next.price()),
                MoneyDto.of(shortfall.isPositive() ? shortfall : Money.zero(next.price().currency())),
                view.state(),
                view.displayStatus(),
                due,
                view.accessProfile(),
                line.getExpiresAt(),
                line.getPauseUntil(),
                view.accessReason(),
                usageLimited && !due,
                view.usage());
    }

    private static Map<String, Long> zeros(final Enum<?>[] names) {
        final Map<String, Long> counts = new LinkedHashMap<>();
        for (final Enum<?> name : names) {
            counts.put(name.name(), 0L);
        }
        return counts;
    }
}
