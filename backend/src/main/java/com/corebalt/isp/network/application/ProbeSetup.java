package com.corebalt.isp.network.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerRequest;
import com.corebalt.isp.network.api.RouterRequest;
import com.corebalt.isp.network.domain.Router;
import com.corebalt.isp.network.infrastructure.RouterRepository;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageRequest;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.subscriptions.api.ProbeLines;
import com.corebalt.isp.tenancy.api.ProbeTenant;
import com.corebalt.isp.tenancy.api.TenantRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform-only setup of the synthetic RADIUS login probe (ADR 0019), run by the {@code probe-setup}
 * command once per hub and safe to run again: the probe tenant (INTERNAL, fixed id), its package and
 * customer, a probe NAS for the hub (a router row at the worker's probe address, with a RADIUS secret no one
 * reads back but FreeRADIUS and the worker), and two lines activated with cause PROBE (S12): one with a
 * far-future expiry, expected FULL, and one long expired, expected RESTRICTED. It never prints or logs a
 * secret.
 */
@Service
public class ProbeSetup {

    /** Expected FULL: well past any Session-Timeout or renewal horizon. */
    public static final Instant FULL_UNTIL = Instant.parse("2099-12-31T00:00:00Z");
    /** Expected RESTRICTED: expired long ago, with no grace (S2). */
    public static final Instant RESTRICTED_SINCE = Instant.parse("2000-01-01T00:00:00Z");

    static final String PACKAGE = "Probe";
    /** An obviously internal number; the probe tenant has no SMS account and no job ever messages it. */
    static final String PHONE = "0700000000";

    public record Result(String nas, String nasAddress, boolean nasCreated, List<ProbeLines.ProbeLine> lines, int linesCreated) {}

    private final TenantRegistry tenants;
    private final PackageCatalog packages;
    private final CustomerFacade customers;
    private final ProbeLines probeLines;
    private final RouterService routerService;
    private final RouterRepository routers;
    private final TransactionTemplate tx;
    private final Clock clock;

    ProbeSetup(final TenantRegistry tenants, final PackageCatalog packages, final CustomerFacade customers,
               final ProbeLines probeLines, final RouterService routerService, final RouterRepository routers,
               final TransactionTemplate tx, final Clock clock) {
        this.tenants = tenants;
        this.packages = packages;
        this.customers = customers;
        this.probeLines = probeLines;
        this.routerService = routerService;
        this.routers = routers;
        this.tx = tx;
        this.clock = clock;
    }

    /** {@code hub}: a letter (A, B); {@code nasAddress}: the IPv4 address the hub's worker probes from. */
    public Result ensure(final String hub, final String nasAddress) {
        final String hubName = hub == null ? "" : hub.strip().toUpperCase(Locale.ROOT);
        if (!hubName.matches("[A-Z]")) {
            throw InvalidRequestException.of("hub", "one letter, such as A or B");
        }
        final String address = ipv4(nasAddress);
        tenants.ensureProbeTenant();
        // One transaction in the probe tenant: the lookups see its rows (T1) and a failure leaves nothing half made.
        return TenantContext.callAsTenant(ProbeTenant.ID, () -> tx.execute(status -> {
            final String nasName = "probe-" + hubName.toLowerCase(Locale.ROOT);
            final var existing = routers.findByNameIgnoreCase(nasName);
            final boolean nasCreated = existing.isEmpty();
            final UUID nasId;
            if (existing.isPresent()) {
                final Router nas = existing.get();
                if (!nas.getTunnelIpA().getHostAddress().equals(address)) {
                    throw InvalidRequestException.of("nasAddress", nasName + " already probes from "
                            + nas.getTunnelIpA().getHostAddress() + "; a probe NAS keeps its address");
                }
                nasId = nas.getId();
            } else {
                // The secret is issued into network_radius_credential and never shown (ADR 0009).
                nasId = routerService.register(new RouterRequest(nasName, address)).router().id();
            }
            final UUID packageId = packages.findByName(PACKAGE).map(p -> p.id()).orElseGet(() -> packages.create(new PackageRequest(
                    PACKAGE, "PPPOE", 1000, 1000, 43_200, new MoneyDto(100L, "KES"), 0, null, null, null, null, null, null)).id());
            final UUID customerId = customers.findByPhone(customers.normalisePhone(PHONE).orElseThrow()).stream()
                    .findFirst().map(c -> c.id())
                    .orElseGet(() -> customers.register(new CustomerRequest(ProbeTenant.NAME, PHONE, null, null, null,
                            null, null, null)).id());
            final Instant now = clock.instant();
            final List<ProbeLines.ProbeLine> current = probeLines.lines();
            int created = 0;
            if (current.stream().noneMatch(l -> "ACTIVE".equals(l.state()) && l.expiresAt().isAfter(now.plus(Duration.ofDays(365))))) {
                probeLines.open(customerId, packageId, nasId, FULL_UNTIL);
                created++;
            }
            if (current.stream().noneMatch(l -> "ACTIVE".equals(l.state()) && l.expiresAt().isBefore(now.minus(Duration.ofDays(30))))) {
                probeLines.open(customerId, packageId, nasId, RESTRICTED_SINCE);
                created++;
            }
            return new Result(nasName, address, nasCreated, probeLines.lines(), created);
        }));
    }

    private static String ipv4(final String value) {
        if (value == null || !value.strip().matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            throw InvalidRequestException.of("nasAddress", "an IPv4 address");
        }
        try {
            return InetAddress.getByName(value.strip()).getHostAddress();
        } catch (UnknownHostException e) {
            throw InvalidRequestException.of("nasAddress", "an IPv4 address");
        }
    }
}
