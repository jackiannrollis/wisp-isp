package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.money.MoneyDto;
import java.time.Instant;
import java.util.UUID;

/**
 * A line as its customer sees it in the portal. {@code displayStatus} and {@code accessProfile} are
 * derived now (S1). {@code renewalPrice} is what the next renewal costs (the scheduled package, S10);
 * {@code payToRenew} is how much more the wallet needs for it, zero when the balance already covers
 * it (B5). It ignores the customer's other lines, which draw on the same wallet.
 */
public record PortalLineDto(
        UUID id,
        String pppoeUsername,
        String packageName,
        int downloadKbps,
        int uploadKbps,
        String nextPackageName,
        MoneyDto renewalPrice,
        MoneyDto payToRenew,
        String state,
        String displayStatus,
        String accessProfile,
        Instant expiresAt,
        Instant pauseUntil) {}
