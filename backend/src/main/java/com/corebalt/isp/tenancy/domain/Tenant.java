package com.corebalt.isp.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One ISP or Wi-Fi vendor on the platform. The tenant registry itself is platform-scoped: it has
 * no {@code tenant_id} and no row-level security policy, so platform code can list tenants.
 */
@Entity
@Table(name = "tenancy_tenant")
public class Tenant {

    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String subdomain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(name = "brand_color", nullable = false)
    private String brandColor;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(nullable = false)
    private String currency;

    @Column(name = "default_grace_days", nullable = false)
    private int defaultGraceDays;

    @Column(name = "grace_mode", nullable = false)
    private String graceMode;

    @Column(name = "max_pause_days", nullable = false)
    private int maxPauseDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Tenant() {}

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSubdomain() {
        return subdomain;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getBrandColor() {
        return brandColor;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getCurrency() {
        return currency;
    }

    public int getDefaultGraceDays() {
        return defaultGraceDays;
    }

    public String getGraceMode() {
        return graceMode;
    }

    public int getMaxPauseDays() {
        return maxPauseDays;
    }
}
