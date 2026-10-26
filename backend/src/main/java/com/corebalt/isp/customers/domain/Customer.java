package com.corebalt.isp.customers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A tenant's customer: profile, contact and location (docs/03). Lines belong to subscriptions. */
@Entity
@Table(name = "customers_customer")
public class Customer {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String phone;

    private String email;

    private String address;

    @Column(name = "installation_location")
    private String installationLocation;

    private BigDecimal lat;

    private BigDecimal lng;

    /** The account reference of the ISP's old system, upper-cased, unique per tenant (ADR 0014, P3). */
    @Column(name = "legacy_account_ref")
    private String legacyAccountRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {}

    public static Customer register(
            final UUID id, final UUID tenantId, final String number, final Profile profile, final Instant now) {
        final Customer customer = new Customer();
        customer.id = id;
        customer.tenantId = tenantId;
        customer.number = number;
        customer.status = CustomerStatus.ACTIVE;
        customer.createdAt = now;
        customer.apply(profile, now);
        return customer;
    }

    public void update(final Profile profile, final Instant now) {
        apply(profile, now);
    }

    /** Archived customers keep their number and history; a number is never reused (T2). */
    public void archive(final Instant now) {
        status = CustomerStatus.ARCHIVED;
        updatedAt = now;
    }

    private void apply(final Profile profile, final Instant now) {
        name = profile.name();
        phone = profile.phone();
        email = profile.email();
        address = profile.address();
        installationLocation = profile.installationLocation();
        lat = profile.lat();
        lng = profile.lng();
        legacyAccountRef = profile.legacyAccountRef();
        updatedAt = now;
    }

    /** Validated profile fields; {@code phone} already in E.164, {@code legacyAccountRef} upper-cased or null. */
    public record Profile(
            String name,
            String phone,
            String email,
            String address,
            String installationLocation,
            BigDecimal lat,
            BigDecimal lng,
            String legacyAccountRef) {}

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getAddress() {
        return address;
    }

    public String getInstallationLocation() {
        return installationLocation;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public String getLegacyAccountRef() {
        return legacyAccountRef;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
