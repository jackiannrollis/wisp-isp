package com.corebalt.isp.payments.infrastructure;

import com.corebalt.isp.payments.api.PaymentDto;
import com.corebalt.isp.payments.application.PaymentService;
import com.corebalt.isp.payments.application.StkService;
import com.corebalt.isp.payments.application.StkService.StkRequestDto;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.web.IdempotentRequests;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's payments and Pay Now (docs/09 portal). The customer is always the token's; Pay Now
 * pushes to the customer's own registered phone only, and an STK request of another customer is a 404.
 */
@RestController
@Tag(name = "Portal")
class PortalPaymentController {

    private final PaymentService payments;
    private final StkService stk;
    private final IdempotentRequests idempotent;

    PortalPaymentController(final PaymentService payments, final StkService stk, final IdempotentRequests idempotent) {
        this.payments = payments;
        this.stk = stk;
        this.idempotent = idempotent;
    }

    record PayRequest(@NotNull @Valid MoneyDto amount) {}

    @GetMapping("/api/v1/portal/payments")
    @Operation(summary = "My payments, newest first")
    PageResponse<PaymentDto> list(
            @RequestParam(defaultValue = "0") final int page, @RequestParam(defaultValue = "20") final int size) {
        return payments.list(CurrentActor.requireCustomerId(),
                PageRequests.of(page, size, null, Set.of(), Sort.by("receivedAt").descending()));
    }

    /**
     * Idempotency-Key required (a money write). Keys are unique per tenant; the customer is part of the
     * request fingerprint, so another customer's key is refused (422), never replayed to them.
     */
    @PostMapping("/api/v1/portal/stk")
    @Operation(summary = "Pay Now: an M-Pesa STK Push to my phone; the payment arrives by callback")
    ResponseEntity<StkRequestDto> payNow(
            @RequestHeader(IdempotentRequests.HEADER) final String key, @Valid @RequestBody final PayRequest request) {
        final UUID customerId = CurrentActor.requireCustomerId();
        final IdempotentRequests.Result<StkRequestDto> result = idempotent.execute(
                key, "POST /portal/stk customer " + customerId, request, StkRequestDto.class,
                () -> stk.initiate(customerId, request.amount().toMoney(), null));
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (result.replayed()) {
            response.header(IdempotentRequests.REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/api/v1/portal/stk/{id}")
    @Operation(summary = "One of my STK requests and its outcome (the pay page polls it)")
    StkRequestDto stkRequest(@PathVariable final UUID id) {
        final StkRequestDto request = stk.get(id);
        if (!request.customerId().equals(CurrentActor.requireCustomerId())) {
            throw new NotFoundException("STK request");
        }
        return request;
    }
}
