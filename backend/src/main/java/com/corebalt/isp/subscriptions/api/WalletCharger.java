package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.money.Money;
import java.util.Currency;
import java.util.UUID;

/**
 * How a renewal or package change is paid for. Implemented in {@code subscriptions.infrastructure}
 * on top of billing's {@code Wallets} (subscriptions already depends on billing, so it calls billing
 * directly; conventions.md, Backend). Called inside the renewal's own transaction: the charge entry
 * and the new expiry commit or roll back together, under billing's wallet row lock (B3), so two lines
 * cannot both spend one payment. The balance is never taken from an event.
 */
public interface WalletCharger {

    /** Unlocked; for decisions and reminder wording. The charge itself re-checks under the lock. */
    Money balance(UUID customerId, Currency currency);

    /** {@code referenceId} is the renewal's or change's own UUID, charged at most once. */
    Outcome charge(UUID customerId, Money amount, Kind kind, UUID referenceId, String description);

    enum Kind {
        RENEWAL,
        PRORATION
    }

    enum Outcome {
        CHARGED,
        ALREADY_CHARGED,
        INSUFFICIENT_BALANCE
    }
}
