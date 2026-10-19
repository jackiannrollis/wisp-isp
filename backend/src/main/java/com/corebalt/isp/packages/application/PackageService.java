package com.corebalt.isp.packages.application;

import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageChanged;
import com.corebalt.isp.packages.api.PackageDto;
import com.corebalt.isp.packages.api.PackageRequest;
import com.corebalt.isp.packages.domain.BillingMode;
import com.corebalt.isp.packages.domain.PackageType;
import com.corebalt.isp.packages.domain.ServicePackage;
import com.corebalt.isp.packages.infrastructure.PackageRepository;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The package catalogue (docs/03 packages). Every change publishes {@link PackageChanged}. */
@Service
public class PackageService implements PackageCatalog {

    private final PackageRepository packages;
    private final OutboxPublisher outbox;
    private final Clock clock;

    PackageService(final PackageRepository packages, final OutboxPublisher outbox, final Clock clock) {
        this.packages = packages;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public PackageDto create(final PackageRequest request) {
        final PackageType type = parse(PackageType.class, "type", request.type(), PackageType.PPPOE);
        requireUniqueName(request.name(), null);
        final Instant now = clock.instant();
        final ServicePackage pkg =
                ServicePackage.define(UuidV7.next(clock), TenantContext.requireTenantId(), type, terms(request), now);
        packages.save(pkg);
        publish(pkg, PackageChanged.Change.CREATED, now);
        return toDto(pkg);
    }

    @Transactional
    public PackageDto revise(final UUID packageId, final PackageRequest request) {
        final ServicePackage pkg = load(packageId);
        if (request.type() != null && parse(PackageType.class, "type", request.type(), null) != pkg.getType()) {
            throw InvalidRequestException.of("type", "cannot change once created");
        }
        requireUniqueName(request.name(), packageId);
        final Instant now = clock.instant();
        pkg.revise(terms(request), now);
        publish(pkg, PackageChanged.Change.REVISED, now);
        return toDto(pkg);
    }

    @Transactional
    public PackageDto setActive(final UUID packageId, final boolean active) {
        final ServicePackage pkg = load(packageId);
        if (pkg.isActive() != active) {
            final Instant now = clock.instant();
            pkg.setActive(active, now);
            publish(pkg, active ? PackageChanged.Change.REACTIVATED : PackageChanged.Change.DEACTIVATED, now);
        }
        return toDto(pkg);
    }

    @Transactional(readOnly = true)
    public PackageDto get(final UUID packageId) {
        return toDto(load(packageId));
    }

    @Transactional(readOnly = true)
    public PageResponse<PackageDto> list(final Boolean active, final Pageable page) {
        return PageResponse.of(
                active == null ? packages.findAll(page) : packages.findByActive(active, page), PackageService::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PackageSummary> find(final UUID packageId) {
        return packages.findById(packageId).map(PackageService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PackageTerms> terms(final UUID packageId) {
        return packages.findById(packageId)
                .map(p -> new PackageTerms(
                        p.getId(), p.getName(), p.getPrice(), p.getDurationMinutes(), p.getGraceDays(),
                        p.getBillingMode() == BillingMode.PREPAID,
                        p.isActive() && p.getType() == PackageType.PPPOE));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PackageSummary> findByName(final String name) {
        return packages.findByNameIgnoreCase(name.strip()).map(PackageService::summary);
    }

    private void requireUniqueName(final String name, final UUID self) {
        packages.findByNameIgnoreCase(name.strip())
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw new ConflictException("A package named '" + other.getName() + "' already exists.");
                });
    }

    private void publish(final ServicePackage pkg, final PackageChanged.Change change, final Instant now) {
        outbox.publish(new PackageChanged(UuidV7.next(clock), pkg.getTenantId(), now, pkg.getId(), change));
    }

    private ServicePackage load(final UUID packageId) {
        return packages.findById(packageId).orElseThrow(() -> new NotFoundException("Package"));
    }

    private static ServicePackage.Terms terms(final PackageRequest request) {
        if ((request.throttleDownloadKbps() == null) != (request.throttleUploadKbps() == null)) {
            throw InvalidRequestException.of("throttleUploadKbps", "throttle speeds come as a pair");
        }
        return new ServicePackage.Terms(
                request.name().strip(),
                request.downloadKbps(),
                request.uploadKbps(),
                request.durationMinutes(),
                request.price().toMoney(),
                request.graceDays(),
                request.dataCapBytes(),
                request.fupBytes(),
                request.throttleDownloadKbps(),
                request.throttleUploadKbps(),
                request.deviceLimit(),
                parse(BillingMode.class, "billingMode", request.billingMode(), BillingMode.PREPAID));
    }

    private static <E extends Enum<E>> E parse(
            final Class<E> type, final String field, final String value, final E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw InvalidRequestException.of(field, "unknown value");
        }
    }

    private static PackageSummary summary(final ServicePackage pkg) {
        return new PackageSummary(pkg.getId(), pkg.getName(), pkg.getType().name(), pkg.isActive());
    }

    static PackageDto toDto(final ServicePackage pkg) {
        return new PackageDto(
                pkg.getId(),
                pkg.getName(),
                pkg.getType().name(),
                pkg.getDownloadKbps(),
                pkg.getUploadKbps(),
                pkg.getDurationMinutes(),
                MoneyDto.of(pkg.getPrice()),
                pkg.getGraceDays(),
                pkg.getDataCapBytes(),
                pkg.getFupBytes(),
                pkg.getThrottleDownloadKbps(),
                pkg.getThrottleUploadKbps(),
                pkg.getDeviceLimit(),
                pkg.getBillingMode().name(),
                pkg.isActive(),
                pkg.getCreatedAt(),
                pkg.getUpdatedAt());
    }
}
