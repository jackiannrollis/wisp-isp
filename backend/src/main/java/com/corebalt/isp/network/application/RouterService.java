package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.RouterDto;
import com.corebalt.isp.network.api.RouterNames;
import com.corebalt.isp.network.api.RouterPollRequested;
import com.corebalt.isp.network.api.RouterRequest;
import com.corebalt.isp.network.api.RouterSecretDto;
import com.corebalt.isp.network.domain.Router;
import com.corebalt.isp.network.infrastructure.RadiusCredentials;
import com.corebalt.isp.network.infrastructure.RouterRepository;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.RouterDirectory;
import com.corebalt.isp.tenancy.api.SecretStore;
import jakarta.persistence.EntityManager;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Router registration, minimal for step 3 (docs/06): a name, the tunnel address on hub A and a
 * platform-generated RADIUS secret. FreeRADIUS loads its clients at start, so a new router or secret
 * takes effect after FreeRADIUS reloads; onboarding scripts and monitoring come later.
 */
@Service
public class RouterService implements RouterDirectory, RouterNames {

    private final RouterRepository routers;
    private final RadiusCredentials credentials;
    private final SecretStore secrets;
    private final JdbcClient jdbc;
    private final EntityManager entities;
    private final OutboxPublisher outbox;
    private final AuditTrail audit;
    private final Clock clock;

    RouterService(final RouterRepository routers, final RadiusCredentials credentials, final SecretStore secrets,
                  final JdbcClient jdbc, final EntityManager entities, final OutboxPublisher outbox, final AuditTrail audit,
                  final Clock clock) {
        this.routers = routers;
        this.credentials = credentials;
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.entities = entities;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RouterSecretDto register(final RouterRequest request) {
        CurrentActor.requireNotImpersonating("Registering a router (its RADIUS secret is shown once)"); // ADR 0017: secrets stay with the tenant
        routers.findByNameIgnoreCase(request.name().strip()).ifPresent(other -> {
            throw new ConflictException("A router named '" + other.getName() + "' already exists.");
        });
        final Router router =
                Router.register(
                        UuidV7.next(clock),
                        TenantContext.requireTenantId(),
                        request.name().strip(),
                        ipv4(request.tunnelIp()),
                        clock.instant());
        routers.saveAndFlush(router);
        return new RouterSecretDto(toDto(router), credentials.issueRouterSecret(router.getId()));
    }

    @Transactional
    public RouterSecretDto regenerateSecret(final UUID routerId) {
        CurrentActor.requireNotImpersonating("A new router RADIUS secret"); // ADR 0017: secrets stay with the tenant
        final Router router = active(routerId);
        return new RouterSecretDto(toDto(router), credentials.regenerateRouterSecret(router.getId()));
    }

    @Transactional(readOnly = true)
    public RouterDto get(final UUID routerId) {
        return toDto(load(routerId));
    }

    /** Active routers, or every router with the retired ones (N10) when {@code includeRetired}. */
    @Transactional(readOnly = true)
    public PageResponse<RouterDto> list(final Pageable page, final boolean includeRetired) {
        return PageResponse.of(includeRetired ? routers.findAll(page) : routers.findByRetiredAtIsNull(page),
                RouterService::toDto);
    }

    /** N10: a retired router takes no new lines. */
    @Override
    @Transactional(readOnly = true)
    public boolean exists(final UUID routerId) {
        return routers.existsByIdAndRetiredAtIsNull(routerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> name(final UUID routerId) {
        return routers.findById(routerId).map(Router::getName);
    }

    /**
     * Sets the RouterOS REST login the tunnel worker polls with (write-only, envelope-encrypted,
     * T4); the router becomes UNKNOWN until its first poll.
     */
    @Transactional
    public RouterDto setRestCredential(final UUID routerId, final String username, final String password) {
        CurrentActor.requireNotImpersonating("A router's REST login"); // ADR 0017: secrets stay with the tenant
        final Router router = active(routerId);
        if (username == null || username.isBlank() || username.length() > 64) {
            throw InvalidRequestException.of("username", "1 to 64 characters");
        }
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw InvalidRequestException.of("password", "12 to 128 characters");
        }
        final byte[] blob = secrets.encrypt(RouterPoller.REST_PURPOSE + router.getId(), password);
        jdbc.sql("""
                        update network_router
                        set rest_username = :username, rest_password_enc = :blob, rest_key_version = :version,
                            status = 'UNKNOWN', poll_misses = 0, offline_since = null, last_poll_error = null, updated_at = :now
                        where id = :id
                        """)
                .param("username", username.strip())
                .param("blob", blob)
                .param("version", java.nio.ByteBuffer.wrap(blob, 1, 4).getInt())
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("id", routerId)
                .update();
        audit.record(AuditEntry.of("network.router_rest_credential_set", "router", routerId, Map.of("username", username.strip())));
        routers.flush();
        return toDto(reload(routerId));
    }

    @Transactional
    public RouterDto removeRestCredential(final UUID routerId) {
        active(routerId);
        jdbc.sql("""
                        update network_router
                        set rest_username = null, rest_password_enc = null, rest_key_version = null, status = 'NOT_MONITORED',
                            poll_misses = 0, offline_since = null, last_poll_error = null, updated_at = :now
                        where id = :id
                        """)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("id", routerId)
                .update();
        audit.record(AuditEntry.of("network.router_rest_credential_removed", "router", routerId));
        return toDto(reload(routerId));
    }

    /** Asks the tunnel worker to poll now (it also polls every 60 s on its own). */
    @Transactional
    public void requestPoll(final UUID routerId) {
        active(routerId);
        outbox.publish(new RouterPollRequested(UuidV7.next(clock), TenantContext.requireTenantId(), clock.instant(), routerId));
    }

    private Router reload(final UUID routerId) {
        final Router router = load(routerId);
        entities.refresh(router);
        return router;
    }

    private Router load(final UUID routerId) {
        return routers.findById(routerId).orElseThrow(() -> new NotFoundException("Router"));
    }

    /** N10: secrets, logins and polls only for a router that is not retired (409 otherwise). */
    private Router active(final UUID routerId) {
        final Router router = load(routerId);
        router.requireActive();
        return router;
    }

    static InetAddress ipv4(final String text) {
        try {
            // A dotted-quad literal never triggers a DNS lookup.
            final InetAddress address = InetAddress.getByName(text.strip());
            if (address instanceof Inet4Address && address.getHostAddress().equals(text.strip())) {
                return address;
            }
        } catch (UnknownHostException e) {
            // reported below
        }
        throw InvalidRequestException.of("tunnelIp", "must be an IPv4 address");
    }

    static RouterDto toDto(final Router router) {
        return new RouterDto(
                router.getId(),
                router.getName(),
                router.getTunnelIpA().getHostAddress(),
                router.getStatus(),
                router.getRestUsername(),
                router.getLastSeenAt(),
                router.getOfflineSince(),
                router.getLastPolledAt(),
                router.getLastPollError(),
                router.getLastAccountingAt(),
                router.getRouterosVersion(),
                router.getBoardName(),
                router.getUptimeSeconds(),
                router.getCpuLoad(),
                router.getWgPublicKey(),
                router.getPppoeInterface(),
                router.getPppLocalAddress() == null ? null : router.getPppLocalAddress().getHostAddress(),
                router.getPoolFull(),
                router.getPoolRestricted(),
                router.getManagementCidrs(),
                router.getOnboardingScriptGeneratedAt(),
                router.getRetiredAt(),
                router.getRetiredReason(),
                router.getReplacedByRouterId(),
                router.getCreatedAt(),
                router.getUpdatedAt());
    }
}
