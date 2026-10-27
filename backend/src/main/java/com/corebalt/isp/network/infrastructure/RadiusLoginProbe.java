package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.ProbeTenant;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The synthetic RADIUS login probe (ADR 0019), in the tunnel worker of each hub: every minute it logs in
 * the probe tenant's two lines through this hub's FreeRADIUS, as the probe NAS of the hub, and checks the
 * answer against what the line's dates require: Access-Accept with {@code Framed-Pool = full} for the line
 * paid far ahead, Access-Accept with {@code Framed-Pool = restricted} for the line expired long ago. So it
 * proves the whole path a router's login takes (client list, view, profile logic), not only that RADIUS
 * answers. Passwords come from {@code v_radius_probe_credential_v1}, the probe tenant's only (T4); they
 * are used for the one packet and never logged.
 *
 * <p>Gauges per hub and line: {@code isp.probe.radius.login.ok} (1 or 0) and
 * {@code isp.probe.radius.login.seconds}; {@code isp.probe.radius.login.failures} counts failures by
 * outcome: REJECT, WRONG_PROFILE, NO_ANSWER, NO_LINE (no such line in the view: not set up, or the line is
 * no longer ACTIVE; {@code probe-setup} opens a new one) and ERROR (the round itself failed, the database unreachable for instance).
 */
@Component
@ConditionalOnProperty(name = "isp.probe.enabled", havingValue = "true")
class RadiusLoginProbe implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RadiusLoginProbe.class);

    enum Line { FULL, RESTRICTED }

    record Credential(String username, String password, Instant expiresAt) {}

    record Outcome(boolean ok, String failure, long millis) {}

    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String hub;
    private final String radiusHost;
    private final int radiusPort;
    private final String nasAddress;
    private final Duration timeout;
    private final Duration interval;
    private final SecureRandom random = new SecureRandom();
    private final Map<Line, AtomicLong> ok = Map.of(Line.FULL, new AtomicLong(), Line.RESTRICTED, new AtomicLong());
    private final Map<Line, AtomicLong> millis = Map.of(Line.FULL, new AtomicLong(), Line.RESTRICTED, new AtomicLong());
    private ScheduledExecutorService executor;

    RadiusLoginProbe(final JdbcClient jdbc, final MeterRegistry meters, final Clock clock,
                     @Value("${isp.worker.hub:A}") final String hub,
                     @Value("${isp.probe.radius-host}") final String radiusHost,
                     @Value("${isp.probe.radius-port:1812}") final int radiusPort,
                     @Value("${isp.probe.nas-address}") final String nasAddress,
                     @Value("${isp.probe.timeout:3s}") final Duration timeout,
                     @Value("${isp.probe.interval:60s}") final Duration interval) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.clock = clock;
        this.hub = hub;
        this.radiusHost = radiusHost;
        this.radiusPort = radiusPort;
        this.nasAddress = nasAddress;
        this.timeout = timeout;
        this.interval = interval;
        for (final Line line : Line.values()) {
            final String tag = line.name().toLowerCase(Locale.ROOT);
            meters.gauge("isp.probe.radius.login.ok", Tags.of("hub", hub, "line", tag), ok.get(line));
            meters.gauge("isp.probe.radius.login.seconds", Tags.of("hub", hub, "line", tag),
                    millis.get(line), m -> m.get() / 1000.0);
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("radius-login-probe").factory());
        executor.scheduleWithFixedDelay(this::probeAll, 15_000, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("RADIUS login probe for hub {}: {}:{} as NAS {} every {}", hub, radiusHost, radiusPort, nasAddress, interval);
    }

    /** One round: both lines, each recorded; never throws. */
    void probeAll() {
        try {
            final List<Credential> credentials = TenantContext.callAsTenant(ProbeTenant.ID, this::credentials);
            final Optional<byte[]> secret = TenantContext.callAsTenant(ProbeTenant.ID, this::nasSecret);
            final Instant now = clock.instant();
            for (final Line line : Line.values()) {
                final Optional<Credential> credential = credentials.stream().filter(c -> expected(c, now) == line).findFirst();
                final Outcome outcome = credential.isEmpty() || secret.isEmpty()
                        ? new Outcome(false, "NO_LINE", 0)
                        : login(credential.get(), secret.get(), line);
                record(line, outcome, credential.map(Credential::username).orElse("-"));
            }
        } catch (RuntimeException e) {
            log.error("RADIUS login probe round failed: {}", e.getClass().getSimpleName());
            for (final Line line : Line.values()) {
                record(line, new Outcome(false, "ERROR", 0), "-");
            }
        }
    }

    /** FULL for an expiry more than a year ahead, RESTRICTED for one more than 30 days past, else nothing to expect. */
    static Line expected(final Credential credential, final Instant now) {
        if (credential.expiresAt() == null) {
            return null;
        }
        if (credential.expiresAt().isAfter(now.plus(Duration.ofDays(365)))) {
            return Line.FULL;
        }
        return credential.expiresAt().isBefore(now.minus(Duration.ofDays(30))) ? Line.RESTRICTED : null;
    }

    Outcome login(final Credential credential, final byte[] secret, final Line line) {
        final int identifier = random.nextInt(256);
        final byte[] authenticator = new byte[16];
        random.nextBytes(authenticator);
        final long started = System.nanoTime();
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(nasAddress), 0))) {
            final byte[] request = RadiusPacket.accessRequest(identifier, authenticator, credential.username(), credential.password(),
                    List.of(RadiusPacket.Attribute.address(RadiusPacket.NAS_IP_ADDRESS,
                            (Inet4Address) InetAddress.getByName(nasAddress))), secret);
            socket.connect(new InetSocketAddress(InetAddress.getByName(radiusHost), radiusPort));
            socket.send(new DatagramPacket(request, request.length));
            final long deadline = started + timeout.toNanos();
            final byte[] buffer = new byte[4096];
            while (true) {
                final long left = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                if (left <= 0) {
                    return new Outcome(false, "NO_ANSWER", 0);
                }
                socket.setSoTimeout((int) left);
                final DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(datagram);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                final RadiusPacket reply;
                try {
                    reply = RadiusPacket.parse(datagram.getData(), datagram.getLength());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (reply.identifier() != identifier || !reply.answers(authenticator, secret)) {
                    continue;
                }
                final long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (reply.code() != RadiusPacket.ACCESS_ACCEPT) {
                    return new Outcome(false, "REJECT", took);
                }
                final String pool = reply.string(RadiusPacket.FRAMED_POOL).orElse("");
                final String want = line == Line.FULL ? "full" : "restricted";
                return pool.equals(want) ? new Outcome(true, null, took) : new Outcome(false, "WRONG_PROFILE", took);
            }
        } catch (IOException e) {
            return new Outcome(false, "NO_ANSWER", 0);
        }
    }

    private void record(final Line line, final Outcome outcome, final String username) {
        ok.get(line).set(outcome.ok() ? 1 : 0);
        if (outcome.ok()) {
            millis.get(line).set(outcome.millis());
            return;
        }
        Counter.builder("isp.probe.radius.login.failures").description("Probe logins that failed, by outcome (ADR 0019)")
                .tag("hub", hub).tag("line", line.name().toLowerCase(Locale.ROOT)).tag("outcome", outcome.failure())
                .register(meters).increment();
        log.warn("RADIUS login probe, hub {}, {} line {}: {}", hub, line, username, outcome.failure());
    }

    private List<Credential> credentials() {
        return jdbc.sql("select username, password, expires_at from v_radius_probe_credential_v1")
                .query((rs, n) -> new Credential(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()))
                .list();
    }

    /** The probe NAS's RADIUS secret, as FreeRADIUS reads it (radius_client_v3); the worker filters by tenant itself. */
    private Optional<byte[]> nasSecret() {
        return jdbc.sql("select secret from radius_client_v3 where tenant_id = :tenant and nasname = :nas")
                .param("tenant", ProbeTenant.ID)
                .param("nas", nasAddress)
                .query(String.class)
                .optional()
                .map(s -> s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    /** For tests: the gauge value of a line. */
    long okOf(final Line line) {
        return ok.get(line).get();
    }
}
