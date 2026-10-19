package com.corebalt.isp.billing.infrastructure;

import com.corebalt.isp.billing.api.WalletDtos.LedgerEntryDto;
import com.corebalt.isp.billing.api.WalletDtos.ReceiptDto;
import com.corebalt.isp.billing.api.WalletDtos.WalletDto;
import com.corebalt.isp.billing.application.WalletService;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's wallet, statement and receipts (docs/09 portal). Staff reasons on adjustments are
 * internal notes and are left out of the customer's statement; the description stays.
 */
@RestController
@Tag(name = "Portal")
class PortalWalletController {

    private final WalletService wallets;

    PortalWalletController(final WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/api/v1/portal/wallet")
    @Operation(summary = "My wallet balance")
    WalletDto wallet() {
        return wallets.wallet(CurrentActor.requireCustomerId(), Money.KES);
    }

    @GetMapping("/api/v1/portal/wallet/entries")
    @Operation(summary = "My statement, newest first")
    PageResponse<LedgerEntryDto> entries(
            @RequestParam(defaultValue = "0") final int page, @RequestParam(defaultValue = "20") final int size) {
        final PageResponse<LedgerEntryDto> entries = wallets.entries(CurrentActor.requireCustomerId(),
                PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
        return new PageResponse<>(entries.items().stream()
                .map(e -> new LedgerEntryDto(e.id(), e.type(), e.amount(), e.balanceAfter(), e.referenceType(),
                        e.referenceId(), e.description(), null, e.createdAt()))
                .toList(), entries.page(), entries.size(), entries.total());
    }

    @GetMapping("/api/v1/portal/receipts")
    @Operation(summary = "My receipts, newest first (B7)")
    PageResponse<ReceiptDto> receipts(
            @RequestParam(defaultValue = "0") final int page, @RequestParam(defaultValue = "20") final int size) {
        return wallets.receipts(CurrentActor.requireCustomerId(), PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }
}
