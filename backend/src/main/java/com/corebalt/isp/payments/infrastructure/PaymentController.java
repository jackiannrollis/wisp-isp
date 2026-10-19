package com.corebalt.isp.payments.infrastructure;

import com.corebalt.isp.payments.api.ManualPaymentRequest;
import com.corebalt.isp.payments.api.PaymentDto;
import com.corebalt.isp.payments.application.PaymentService;
import com.corebalt.isp.shared.web.IdempotentRequests;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
class PaymentController {

    private static final Set<String> SORTABLE = Set.of("receivedAt", "createdAt");

    private final PaymentService service;
    private final IdempotentRequests idempotent;

    PaymentController(final PaymentService service, final IdempotentRequests idempotent) {
        this.service = service;
        this.idempotent = idempotent;
    }

    @GetMapping
    @Operation(summary = "List payments, optionally of one customer")
    PageResponse<PaymentDto> list(
            @RequestParam(required = false) final UUID customerId,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size,
            @RequestParam(required = false) final String sort) {
        return service.list(customerId, PageRequests.of(page, size, sort, SORTABLE, Sort.by("receivedAt").descending()));
    }

    @PostMapping("/manual")
    @PreAuthorize("hasAuthority('payments.record_manual')")
    @Operation(summary = "Record a cash, bank or M-Pesa payment by hand (P5); the wallet is credited from it")
    ResponseEntity<PaymentDto> recordManual(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @Valid @RequestBody final ManualPaymentRequest request) {
        final IdempotentRequests.Result<PaymentDto> result =
                idempotent.execute(key, "POST /payments/manual", request, PaymentDto.class, () -> service.recordManual(request));
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(IdempotentRequests.REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }
}
