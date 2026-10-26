package com.corebalt.isp.network.application;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upkeep of RADIUS accounting (docs/06 Accounting, N7): monthly partitions, sessions no router
 * reports on any more, the usage roll-up into {@code network_usage_daily} (the only usage source for
 * screens), and each router's "last heard" from accounting. Access never depends on any of it (E4).
 */
@Service
public class Accounting {

    /** Kept, counted back from the current month (N7). */
    public static final int RETENTION_MONTHS = 13;
    public static final int MONTHS_AHEAD = 3;
    /** A session without an interim update for this long is closed as Stale (the lab sets interim-update=5m). */
    public static final Duration STALE_AFTER = Duration.ofMinutes(15);
    /** Stopped sessions are rolled up for this long after they stop, then their high-water mark goes. */
    static final Duration ROLL_UP_TAIL = Duration.ofDays(2);

    private final JdbcClient jdbc;
    private final TenantProfile tenant;
    private final CycleUsage cycleUsage;
    private final Clock clock;

    Accounting(final JdbcClient jdbc, final TenantProfile tenant, final CycleUsage cycleUsage, final Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.cycleUsage = cycleUsage;
        this.clock = clock;
    }

    /** Platform scope: creates this month and the next {@link #MONTHS_AHEAD}; returns how many were new. */
    @Transactional
    public int ensurePartitions() {
        return jdbc.sql("select network_ensure_radacct_partitions(:ahead)").param("ahead", MONTHS_AHEAD)
                .query(Integer.class).single();
    }

    /** Platform scope: drops months past retention; returns how many partitions went. */
    @Transactional
    public int dropExpiredPartitions() {
        return jdbc.sql("select network_drop_radacct_partitions(:keep)").param("keep", RETENTION_MONTHS)
                .query(Integer.class).single();
    }

    /**
     * Closes the current tenant's sessions with no update for {@link #STALE_AFTER}: stop time is the
     * last update, cause {@code Stale}. A later interim or stop from the router reopens or completes
     * the same row (radius/raddb/mods-available/sql).
     */
    @Transactional
    public int closeStaleSessions() {
        return jdbc.sql("""
                        update radacct
                        set acctstoptime = coalesce(acctupdatetime, acctstarttime), acctterminatecause = 'Stale'
                        where acctstoptime is null and coalesce(acctupdatetime, acctstarttime) < :cutoff
                        """)
                .param("cutoff", Timestamp.from(clock.instant().minus(STALE_AFTER)))
                .update();
    }

    /**
     * N10: a retired router's open sessions end at their last update, cause {@code Router-Retired}, so no
     * CoA or Disconnect is ever aimed at a router FreeRADIUS no longer knows (AccessChanges reads radacct).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int closeSessionsOf(final UUID routerId) {
        return jdbc.sql("""
                        update radacct
                        set acctstoptime = coalesce(acctupdatetime, acctstarttime), acctterminatecause = 'Router-Retired'
                        where acctstoptime is null and router_id = :routerId
                        """)
                .param("routerId", routerId)
                .update();
    }

    /** Secondary liveness: the newest accounting packet per router of the current tenant. */
    @Transactional
    public int recordLastHeard() {
        return jdbc.sql("""
                        update network_router r set last_accounting_at = x.heard
                        from (select router_id, max(coalesce(acctupdatetime, acctstarttime)) as heard
                              from radacct
                              where router_id is not null and (acctstoptime is null or acctstoptime > :since)
                              group by router_id) x
                        where r.id = x.router_id and (r.last_accounting_at is null or r.last_accounting_at < x.heard)
                        """)
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofHours(1))))
                .update();
    }

    /**
     * Adds the octets each session grew by since the last run to the tenant-local day this run
     * falls in, and moves the session's high-water mark (N7). One transaction under a per-tenant
     * advisory lock, so two API instances never count the same growth twice. Returns the number of
     * lines whose day changed, or -1 when another instance holds the lock.
     */
    @Transactional
    public int rollUpUsage() {
        final boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(hashtext('network-usage:' || :tenant))")
                .param("tenant", TenantContext.requireTenantId().toString()).query(Boolean.class).single();
        if (!locked) {
            return -1;
        }
        final Instant now = clock.instant();
        final LocalDate day = LocalDate.ofInstant(now, tenant.currentPolicy().zone());
        final Map<UUID, Long> growth = jdbc.sql("""
                        with current_sessions as (
                            select a.acctuniqueid, a.acctstarttime, a.subscription_id, a.acctinputoctets, a.acctoutputoctets
                            from radacct a
                            where a.subscription_id is not null and (a.acctstoptime is null or a.acctstoptime > :tail)
                        ), growth as (
                            select c.*,
                                   greatest(c.acctinputoctets - coalesce(h.input_octets, 0), 0) as input_delta,
                                   greatest(c.acctoutputoctets - coalesce(h.output_octets, 0), 0) as output_delta
                            from current_sessions c
                            left join network_usage_session h
                                on h.acctuniqueid = c.acctuniqueid and h.acctstarttime = c.acctstarttime
                        ), marks as (
                            insert into network_usage_session
                                (tenant_id, acctuniqueid, acctstarttime, subscription_id, input_octets, output_octets, last_seen_at)
                            select :tenantId, acctuniqueid, acctstarttime, subscription_id, acctinputoctets, acctoutputoctets, :now
                            from growth
                            on conflict (tenant_id, acctuniqueid, acctstarttime) do update
                            set input_octets = greatest(network_usage_session.input_octets, excluded.input_octets),
                                output_octets = greatest(network_usage_session.output_octets, excluded.output_octets),
                                last_seen_at = excluded.last_seen_at
                        ), per_line as (
                            select subscription_id, sum(input_delta) as input_delta, sum(output_delta) as output_delta
                            from growth
                            where input_delta > 0 or output_delta > 0
                            group by subscription_id
                        ), daily as (
                            insert into network_usage_daily (tenant_id, subscription_id, day, input_octets, output_octets, updated_at)
                            select :tenantId, subscription_id, :day, input_delta, output_delta, :now
                            from per_line
                            on conflict (tenant_id, subscription_id, day) do update
                            set input_octets = network_usage_daily.input_octets + excluded.input_octets,
                                output_octets = network_usage_daily.output_octets + excluded.output_octets,
                                updated_at = excluded.updated_at
                        )
                        select subscription_id, input_delta + output_delta from per_line
                        """)
                .param("tail", Timestamp.from(now.minus(ROLL_UP_TAIL)))
                .param("tenantId", TenantContext.requireTenantId())
                .param("now", Timestamp.from(now))
                .param("day", Date.valueOf(day))
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getLong(2)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // The same growth, added to each line's cycle total for fair use and data caps (docs/09 step 8b).
        cycleUsage.add(growth);
        jdbc.sql("delete from network_usage_session where last_seen_at < :expired")
                .param("expired", Timestamp.from(now.minus(ROLL_UP_TAIL.plusDays(1))))
                .update();
        return growth.size();
    }
}
