package com.corebalt.isp.billing.infrastructure;

import com.corebalt.isp.billing.api.WalletDtos.AdjustmentRequest;
import com.corebalt.isp.billing.api.WalletDtos.LedgerEntryDto;
import com.corebalt.isp.billing.api.WalletDtos.ReceiptDto;
import com.corebalt.isp.billing.api.WalletDtos.WalletDto;
import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.shared.web.IdempotentRequests;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Billing")
class WalletController {

    private static final Currency KES = Currency.getInstance("KES");

    private final WalletService wallets;
    private final IdempotentRequests idempotent;

    WalletController(final WalletService wallets, final IdempotentRequests idempotent) {
        this.wallets = wallets;
        this.idempotent = idempotent;
    }

    @GetMapping("/api/v1/customers/{customerId}/wallet")
    @PreAuthorize("hasAuthority('finance.read')")
    @Operation(summary = "The customer's wallet balance")
    WalletDto wallet(@PathVariable final UUID customerId) {
        return wallets.wallet(customerId, KES);
    }

    @GetMapping("/api/v1/customers/{customerId}/wallet/entries")
    @PreAuthorize("hasAuthority('finance.read')")
    @Operation(summary = "The customer's ledger entries, newest first (statement)")
    PageResponse<LedgerEntryDto> entries(
            @PathVariable final UUID customerId,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size) {
        return wallets.entries(customerId, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @PostMapping("/api/v1/customers/{customerId}/wallet/adjustments")
    @PreAuthorize("hasAuthority('billing.adjust')")
    @Operation(summary = "Post an ADJUSTMENT with a reason (B1); a credit renews lines like a payment")
    ResponseEntity<LedgerEntryDto> adjust(
            @RequestHeader(IdempotentRequests.HEADER) final String key,
            @PathVariable final UUID customerId,
            @Valid @RequestBody final AdjustmentRequest request) {
        final IdempotentRequests.Result<LedgerEntryDto> result = idempotent.execute(
                key, "POST /customers/" + customerId + "/wallet/adjustments", request, LedgerEntryDto.class,
                () -> wallets.adjust(customerId, request.amount().toMoney(), request.reason().strip()));
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(IdempotentRequests.REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/api/v1/receipts")
    @PreAuthorize("hasAuthority('finance.read')")
    @Operation(summary = "Receipts, newest first, optionally of one customer (B7)")
    PageResponse<ReceiptDto> receipts(
            @RequestParam(required = false) final UUID customerId,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size) {
        return wallets.receipts(customerId, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }
}
