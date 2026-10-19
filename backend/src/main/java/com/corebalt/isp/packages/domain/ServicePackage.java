package com.corebalt.isp.packages.domain;

import com.corebalt.isp.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A package in the tenant's catalogue (docs/03 packages). Named {@code ServicePackage} because
 * {@code Package} is taken by {@code java.lang}. Speeds are kbit/s; the RADIUS view writes them as
 * {@code upload/download} (N4).
 */
@Entity
@Table(name = "packages_package")
public class ServicePackage {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PackageType type;

    @Column(name = "download_kbps", nullable = false)
    private int downloadKbps;

    @Column(name = "upload_kbps", nullable = false)
    private int uploadKbps;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "grace_days")
    private Integer graceDays;

    @Column(name = "data_cap_bytes")
    private Long dataCapBytes;

    @Column(name = "fup_bytes")
    private Long fupBytes;

    @Column(name = "throttle_download_kbps")
    private Integer throttleDownloadKbps;

    @Column(name = "throttle_upload_kbps")
    private Integer throttleUploadKbps;

    @Column(name = "device_limit")
    private Integer deviceLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_mode", nullable = false)
    private BillingMode billingMode;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServicePackage() {}

    public static ServicePackage define(
            final UUID id, final UUID tenantId, final PackageType type, final Terms terms, final Instant now) {
        final ServicePackage pkg = new ServicePackage();
        pkg.id = id;
        pkg.tenantId = tenantId;
        pkg.type = type;
        pkg.active = true;
        pkg.createdAt = now;
        pkg.apply(terms, now);
        return pkg;
    }

    /**
     * New terms apply to future sales and renewals; a cycle already paid for is never changed (S6),
     * which the subscription guarantees by copying what it needs at renewal.
     */
    public void revise(final Terms terms, final Instant now) {
        apply(terms, now);
    }

    /** Inactive packages stay on existing lines but cannot be sold (docs/03). */
    public void setActive(final boolean active, final Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    private void apply(final Terms terms, final Instant now) {
        if ((terms.throttleDownloadKbps() == null) != (terms.throttleUploadKbps() == null)) {
            throw new IllegalArgumentException("throttle speeds come as a pair");
        }
        name = terms.name();
        downloadKbps = terms.downloadKbps();
        uploadKbps = terms.uploadKbps();
        durationMinutes = terms.durationMinutes();
        priceMinor = terms.price().minorUnits();
        currency = terms.price().currency().getCurrencyCode();
        graceDays = terms.graceDays();
        dataCapBytes = terms.dataCapBytes();
        fupBytes = terms.fupBytes();
        throttleDownloadKbps = terms.throttleDownloadKbps();
        throttleUploadKbps = terms.throttleUploadKbps();
        deviceLimit = terms.deviceLimit();
        billingMode = terms.billingMode();
        updatedAt = now;
    }

    /** The commercial terms staff edit. */
    public record Terms(
            String name,
            int downloadKbps,
            int uploadKbps,
            int durationMinutes,
            Money price,
            Integer graceDays,
            Long dataCapBytes,
            Long fupBytes,
            Integer throttleDownloadKbps,
            Integer throttleUploadKbps,
            Integer deviceLimit,
            BillingMode billingMode) {}

    public Money getPrice() {
        return Money.ofMinor(priceMinor, currency);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public PackageType getType() {
        return type;
    }

    public int getDownloadKbps() {
        return downloadKbps;
    }

    public int getUploadKbps() {
        return uploadKbps;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public Long getDataCapBytes() {
        return dataCapBytes;
    }

    public Long getFupBytes() {
        return fupBytes;
    }

    public Integer getThrottleDownloadKbps() {
        return throttleDownloadKbps;
    }

    public Integer getThrottleUploadKbps() {
        return throttleUploadKbps;
    }

    public Integer getDeviceLimit() {
        return deviceLimit;
    }

    public BillingMode getBillingMode() {
        return billingMode;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
