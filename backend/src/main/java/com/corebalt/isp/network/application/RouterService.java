package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.RouterDto;
import com.corebalt.isp.network.api.RouterRequest;
import com.corebalt.isp.network.api.RouterSecretDto;
import com.corebalt.isp.network.domain.Router;
import com.corebalt.isp.network.infrastructure.RadiusCredentials;
import com.corebalt.isp.network.infrastructure.RouterRepository;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.subscriptions.api.RouterDirectory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Router registration, minimal for step 3 (docs/06): a name, the tunnel address on hub A and a
 * platform-generated RADIUS secret. FreeRADIUS loads its clients at start, so a new router or secret
 * takes effect after FreeRADIUS reloads; onboarding scripts and monitoring come later.
 */
@Service
public class RouterService implements RouterDirectory {

    private final RouterRepository routers;
    private final RadiusCredentials credentials;
    private final Clock clock;

    RouterService(final RouterRepository routers, final RadiusCredentials credentials, final Clock clock) {
        this.routers = routers;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Transactional
    public RouterSecretDto register(final RouterRequest request) {
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
        final Router router = load(routerId);
        return new RouterSecretDto(toDto(router), credentials.regenerateRouterSecret(router.getId()));
    }

    @Transactional(readOnly = true)
    public RouterDto get(final UUID routerId) {
        return toDto(load(routerId));
    }

    @Transactional(readOnly = true)
    public PageResponse<RouterDto> list(final Pageable page) {
        return PageResponse.of(routers.findAll(page), RouterService::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(final UUID routerId) {
        return routers.existsById(routerId);
    }

    private Router load(final UUID routerId) {
        return routers.findById(routerId).orElseThrow(() -> new NotFoundException("Router"));
    }

    private static InetAddress ipv4(final String text) {
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

    private static RouterDto toDto(final Router router) {
        return new RouterDto(
                router.getId(),
                router.getName(),
                router.getTunnelIpA().getHostAddress(),
                router.getCreatedAt(),
                router.getUpdatedAt());
    }
}
