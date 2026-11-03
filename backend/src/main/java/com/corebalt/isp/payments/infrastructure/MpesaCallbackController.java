package com.corebalt.isp.payments.infrastructure;

import com.corebalt.isp.payments.application.CallbackInbox;
import com.corebalt.isp.payments.application.CallbackInbox.Kind;
import com.corebalt.isp.payments.application.MpesaSettings;
import com.corebalt.isp.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daraja callbacks (docs/05 Callback handling): outside {@code /api/v1}, no JWT, source IP checked by
 * {@code CallbackSourceFilter}, tenant from the path token. Every body is stored before anything
 * else (P2) and acknowledged at once with ResultCode 0; processing happens after the response.
 * Validation accepts every payment (Q10 default: rely on the suspense queue).
 */
@RestController
@RequestMapping({"/callbacks/payments/{token}", "/callbacks/mpesa/{token}"})
@Hidden
class MpesaCallbackController {

    static final Map<String, Object> ACCEPTED = Map.of("ResultCode", 0, "ResultDesc", "Accepted");

    private final MpesaSettings settings;
    private final CallbackInbox inbox;

    MpesaCallbackController(final MpesaSettings settings, final CallbackInbox inbox) {
        this.settings = settings;
        this.inbox = inbox;
    }

    @PostMapping("/c2b/validation")
    ResponseEntity<Map<String, Object>> validation(
            @PathVariable final String token, @RequestBody(required = false) final String body, final HttpServletRequest request) {
        return receive(token, Kind.C2B_VALIDATION, body, request);
    }

    @PostMapping("/c2b/confirmation")
    ResponseEntity<Map<String, Object>> confirmation(
            @PathVariable final String token, @RequestBody(required = false) final String body, final HttpServletRequest request) {
        return receive(token, Kind.C2B_CONFIRMATION, body, request);
    }

    @PostMapping("/stk")
    ResponseEntity<Map<String, Object>> stk(
            @PathVariable final String token, @RequestBody(required = false) final String body, final HttpServletRequest request) {
        return receive(token, Kind.STK_RESULT, body, request);
    }

    @PostMapping("/txn-status/result")
    ResponseEntity<Map<String, Object>> transactionStatusResult(
            @PathVariable final String token, @RequestBody(required = false) final String body, final HttpServletRequest request) {
        return receive(token, Kind.TXN_STATUS_RESULT, body, request);
    }

    @PostMapping("/txn-status/timeout")
    ResponseEntity<Map<String, Object>> transactionStatusTimeout(
            @PathVariable final String token, @RequestBody(required = false) final String body, final HttpServletRequest request) {
        return receive(token, Kind.TXN_STATUS_TIMEOUT, body, request);
    }

    private ResponseEntity<Map<String, Object>> receive(
            final String token, final Kind kind, final String body, final HttpServletRequest request) {
        final Optional<UUID> tenantId = TenantContext.callAsPlatform("mpesa-callback-route", () -> settings.tenantForToken(token));
        if (tenantId.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        inbox.receive(tenantId.get(), kind, body, request.getRemoteAddr());
        return ResponseEntity.ok(ACCEPTED);
    }
}
