package com.corebalt.isp.tenancy.application;

import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.AccountSettingsDto;
import com.corebalt.isp.tenancy.domain.Tenant;
import com.corebalt.isp.tenancy.infrastructure.TenantRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current tenant's own settings (settings.write; decisions.md 2026-10-21). The tenant registry has
 * no row-level security, so the row is always the one of the tenant context, never one named by the
 * request (T1). Every change is audited with the fields before and after (A1).
 */
@Service
public class AccountSettings {

    private final TenantRepository tenants;
    private final AuditTrail audit;
    private final Clock clock;

    AccountSettings(final TenantRepository tenants, final AuditTrail audit, final Clock clock) {
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccountSettingsDto view() {
        return toDto(current());
    }

    @Transactional
    public AccountSettingsDto update(final AccountSettingsDto.Request request) {
        final Tenant tenant = current();
        final Map<String, Object> before = fields(tenant);
        tenant.updateSettings(request.name().strip(), request.brandColor().toLowerCase(),
                request.logoUrl() == null || request.logoUrl().isBlank() ? null : request.logoUrl().strip(),
                request.defaultGraceDays(), request.graceMode(), request.partialPaymentRule(), request.maxPauseDays(),
                clock.instant());
        tenants.save(tenant);
        final Map<String, Object> after = fields(tenant);
        final Map<String, Object> changedBefore = new LinkedHashMap<>();
        final Map<String, Object> changedAfter = new LinkedHashMap<>();
        after.forEach((key, value) -> {
            if (!Objects.equals(before.get(key), value)) {
                changedBefore.put(key, before.get(key));
                changedAfter.put(key, value);
            }
        });
        if (!changedAfter.isEmpty()) {
            audit.record(new AuditEntry("tenancy.settings_changed", "tenant", tenant.getId(), changedBefore, changedAfter, null));
        }
        return toDto(tenant);
    }

    private Tenant current() {
        final var id = TenantContext.requireTenantId();
        return tenants.findById(id).orElseThrow(() -> new IllegalStateException("T1: no tenant row for " + id));
    }

    private static Map<String, Object> fields(final Tenant tenant) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", tenant.getName());
        fields.put("brandColor", tenant.getBrandColor());
        fields.put("logoUrl", tenant.getLogoUrl());
        fields.put("defaultGraceDays", tenant.getDefaultGraceDays());
        fields.put("graceMode", tenant.getGraceMode());
        fields.put("partialPaymentRule", tenant.getPartialPaymentRule());
        fields.put("maxPauseDays", tenant.getMaxPauseDays());
        return fields;
    }

    private static AccountSettingsDto toDto(final Tenant tenant) {
        return new AccountSettingsDto(tenant.getCode(), tenant.getName(), tenant.getBrandColor(), tenant.getLogoUrl(),
                tenant.getTimeZone(), tenant.getCurrency(), tenant.getDefaultGraceDays(), tenant.getGraceMode(),
                tenant.getPartialPaymentRule(), tenant.getMaxPauseDays(), tenant.getCutoverWindowUntil());
    }
}
