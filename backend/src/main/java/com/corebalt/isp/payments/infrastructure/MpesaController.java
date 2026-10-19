package com.corebalt.isp.payments.infrastructure;

import com.corebalt.isp.payments.api.PaymentDto;
import com.corebalt.isp.payments.application.MpesaSettings;
import com.corebalt.isp.payments.application.MpesaSettings.SettingsRequest;
import com.corebalt.isp.payments.application.MpesaSettings.SettingsView;
import com.corebalt.isp.payments.application.PaymentService;
import com.corebalt.isp.payments.application.PaymentService.SuspenseItem;
import com.corebalt.isp.payments.application.StkService;
import com.corebalt.isp.payments.application.StkService.StkRequestDto;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.web.IdempotentRequests;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** M-Pesa settings, STK Push and the suspense queue (docs/05). */
@RestController
@Tag(name = "Payments")
class MpesaController {

    private final MpesaSettings settings;
    private final StkService stk;
    private final PaymentService payments;
    private final IdempotentRequests idempotent;

    MpesaController(
            final MpesaSettings settings, final StkService stk, final PaymentService payments, final IdempotentRequests idempotent) {
        this.settings = settings;
        this.stk = stk;
        this.payments = payments;
        this.idempotent = idempotent;
    }

    @PutMapping("/api/v1/settings/mpesa")
    @PreAuthorize("hasAuthority('settings.secrets')")
    @Operation(summary = "Set the Paybill or Till and its Daraja credentials (write-only); returns the callback URLs")
    SettingsView configure(@Valid @RequestBody final SettingsRequest request) {
        return settings.configure(request);
    }

    @GetMapping("/api/v1/settings/mpesa")
    @PreAuthorize("hasAuthority('settings.secrets')")
    @Operation(summary = "The M-Pesa settings and callback URLs (never the credentials)")
    SettingsView view() {
        return settings.view();
    }

    @PostMapping("/api/v1/settings/mpesa/register-urls")
    @PreAuthorize("hasAuthority('settings.secrets')")
    @Operation(summary = "Register the C2B confirmation and validation URLs with Safaricom")
    SettingsView registerUrls() {
        return settings.registerUrls();
    }

    record StkRequest(@NotNull UUID customerId, @NotNull @Valid MoneyDto amount, @Size(max = 20) String phone) {}

    @PostMapping("/api/v1/payments/stk")
    @PreAuthorize("hasAuthority('payments.stk')")
    @Operation(summary = "Send an STK Push to the customer's phone (or another); the payment arrives by callback")
    ResponseEntity<StkRequestDto> stkPush(
            @RequestHeader(IdempotentRequests.HEADER) final String key, @Valid @RequestBody final StkRequest request) {
        final IdempotentRequests.Result<StkRequestDto> result = idempotent.execute(
                key, "POST /payments/stk", request, StkRequestDto.class,
                () -> stk.initiate(request.customerId(), request.amount().toMoney(), request.phone()));
        return withReplay(ResponseEntity.status(HttpStatus.ACCEPTED), result);
    }

    @GetMapping("/api/v1/payments/stk/{id}")
    @Operation(summary = "One STK request and its outcome")
    StkRequestDto stkRequest(@PathVariable final UUID id) {
        return stk.get(id);
    }

    @GetMapping("/api/v1/payments/suspense")
    @Operation(summary = "Unmatched payments with a likely-payer shortlist for masked numbers (P3)")
    PageResponse<SuspenseItem> suspense(
            @RequestParam(defaultValue = "0") final int page, @RequestParam(defaultValue = "50") final int size) {
        return payments.suspense(PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    record AssignRequest(@NotNull UUID customerId) {}

    @PostMapping("/api/v1/payments/{id}/assign")
    @PreAuthorize("hasAuthority('payments.assign')")
    @Operation(summary = "Assign an unmatched payment to a customer; the wallet is credited from it")
    ResponseEntity<PaymentDto> assign(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID id,
            @Valid @RequestBody final AssignRequest request) {
        final IdempotentRequests.Result<PaymentDto> result = idempotent.execute(
                key, "POST /payments/" + id + "/assign", request, PaymentDto.class,
                () -> payments.assign(id, request.customerId()));
        return withReplay(ResponseEntity.ok(), result);
    }

    private static <T> ResponseEntity<T> withReplay(final ResponseEntity.BodyBuilder response, final IdempotentRequests.Result<T> result) {
        if (result.replayed()) {
            response.header(IdempotentRequests.REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }
}
