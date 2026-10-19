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
import com.corebalt.isp.subscriptions.domain.GraceMode;
import com.corebalt.isp.subscriptions.domain.Subscription;
import com.corebalt.isp.subscriptions.domain.SubscriptionState;
import com.corebalt.isp.subscriptions.infrastructure.SubscriptionRepository;
import com.corebalt.isp.tenancy.api.TenantProfile;
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
 * customer's lines. Profiles and display statuses come from {@link AccessPolicy} and
 * {@link DisplayStatus} at read time (S1, S3); nothing here stores or recomputes them in SQL.
 */
@Service
public class LineStatisticsService implements LineStatistics {

    private final JdbcClient jdbc;
    private final SubscriptionRepository lines;
    private final SubscriptionViews views;
    private final PackageCatalog packages;
    private final Wallets wallets;
    private final TenantProfile tenantProfile;

    LineStatisticsService(final JdbcClient jdbc, final SubscriptionRepository lines, final SubscriptionViews views,
                          final PackageCatalog packages, final Wallets wallets, final TenantProfile tenantProfile) {
        this.jdbc = jdbc;
        this.lines = lines;
        this.views = views;
        this.packages = packages;
        this.wallets = wallets;
        this.tenantProfile = tenantProfile;
    }

    @Override
    @Transactional(readOnly = true)
    public LineCounts counts(final Instant now) {
        final GraceMode graceMode = GraceMode.valueOf(tenantProfile.currentPolicy().graceMode());
        final Map<String, Long> byState = zeros(SubscriptionState.values());
        final Map<String, Long> byDisplay = zeros(DisplayStatus.values());
        final long[] total = {0};
        jdbc.sql("select state, expires_at, grace_days, paused_at, pause_until from subscriptions_subscription")
                .query(rs -> {
                    final AccessPolicy.Facts facts = new AccessPolicy.Facts(
                            SubscriptionState.valueOf(rs.getString(1)), instant(rs.getTimestamp(2)), rs.getInt(3),
                            instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)));
                    final AccessPolicy.Decision decision = AccessPolicy.evaluate(facts, graceMode, now);
                    byState.merge(facts.state().name(), 1L, Long::sum);
                    byDisplay.merge(DisplayStatus.of(facts, decision, now).name(), 1L, Long::sum);
                    total[0]++;
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

    /**
     * The customer's lines, earliest opened first (terminated lines left out), the wallet balance, and
     * "pay all": what the wallet lacks to renew every due line at once, max(0, sum of their renewal
     * prices - balance). Per-line Pay Now uses each line's own shortfall.
     */
    @Transactional(readOnly = true)
    public PortalLinesDto portalLines(final UUID customerId) {
        final SubscriptionViews.Mapper mapper = views.mapper();
        final Money balance = wallets.balance(customerId, Money.KES);
        final List<PortalLineDto> mine = lines.findByCustomerId(customerId, Pageable.unpaged(Sort.by("createdAt"))).stream()
                .filter(line -> line.getState() != SubscriptionState.TERMINATED)
                .map(line -> portalLine(line, mapper.toDto(line), balance))
                .toList();
        Money dueTotal = Money.zero(Money.KES);
        for (final PortalLineDto line : mine) {
            dueTotal = line.due() ? dueTotal.plus(line.renewalPrice().toMoney()) : dueTotal;
        }
        final Money payAll = dueTotal.minus(balance);
        return new PortalLinesDto(mine, MoneyDto.of(balance), MoneyDto.of(payAll.isPositive() ? payAll : Money.zero(Money.KES)));
    }

    private PortalLineDto portalLine(final Subscription line, final SubscriptionDto view, final Money balance) {
        final PackageTerms current = packages.terms(line.getPackageId()).orElseThrow();
        final PackageSpeeds speeds = packages.speeds(line.getPackageId()).orElseThrow();
        final PackageTerms next = packages.terms(line.packageForNextRenewal()).orElse(current);
        final Money shortfall = next.price().minus(balance);
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
                DUE.contains(view.displayStatus()),
                view.accessProfile(),
                line.getExpiresAt(),
                line.getPauseUntil());
    }

    private static Map<String, Long> zeros(final Enum<?>[] names) {
        final Map<String, Long> counts = new LinkedHashMap<>();
        for (final Enum<?> name : names) {
            counts.put(name.name(), 0L);
        }
        return counts;
    }

    private static Instant instant(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
