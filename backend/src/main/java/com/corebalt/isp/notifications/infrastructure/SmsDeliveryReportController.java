package com.corebalt.isp.notifications.infrastructure;

import com.corebalt.isp.notifications.application.SmsSender;
import com.corebalt.isp.notifications.application.SmsSettings;
import com.corebalt.isp.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Historical Africa's Talking delivery reports only (form-encoded {@code id}, {@code status},
 * {@code failureReason}, ...). No JWT; the tenant comes from the path token. Africa's Talking does
 * not publish fixed source addresses, so this path has no IP allowlist: the unguessable token is
 * the guard, and a report can only move an existing message's status.
 */
@RestController
@RequestMapping("/callbacks/sms/{token}")
@Hidden
class SmsDeliveryReportController {

    private final SmsSettings settings;
    private final SmsSender sender;

    SmsDeliveryReportController(final SmsSettings settings, final SmsSender sender) {
        this.settings = settings;
        this.sender = sender;
    }

    @PostMapping(path = "/delivery", consumes = "application/x-www-form-urlencoded")
    ResponseEntity<Void> delivery(
            @PathVariable final String token,
            @RequestParam(name = "id", required = false) final String id,
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "failureReason", required = false) final String failureReason) {
        final Optional<UUID> tenantId = TenantContext.callAsPlatform("sms-delivery-route", () -> settings.tenantForToken(token));
        if (tenantId.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (id != null && !id.isBlank()) {
            TenantContext.runAsTenant(tenantId.get(), () -> sender.deliveryReport(id.strip(), status, failureReason));
        }
        return ResponseEntity.ok().build();
    }
}
