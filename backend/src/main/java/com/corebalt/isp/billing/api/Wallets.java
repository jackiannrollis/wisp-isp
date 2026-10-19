package com.corebalt.isp.billing.api;

import com.corebalt.isp.shared.money.Money;
import java.util.Currency;
import java.util.UUID;

/**
 * The billing module's interface for charges other modules cause (docs/05). Every call runs in the
 * caller's transaction under the wallet row lock (B3), so a charge and the change it pays for commit
 * or roll back together, and two charges cannot both spend one balance. Scoped to the current
 * tenant (T1).
 */
public interface Wallets {

    /** The customer's balance; zero when the customer has no wallet yet. */
    Money balance(UUID customerId, Currency currency);

    /**
     * Debits {@code amount} if the balance covers it (B5). {@code referenceId} identifies the cause
     * (a renewal or a package change) and is charged at most once: a repeat returns
     * {@link ChargeResult#ALREADY_CHARGED} without a second entry.
     */
    ChargeResult charge(UUID customerId, Money amount, ChargeType type, UUID referenceId, String description);

    enum ChargeType {
        RENEWAL_CHARGE,
        PRORATION_CHARGE
    }

    enum ChargeResult {
        CHARGED,
        ALREADY_CHARGED,
        INSUFFICIENT_BALANCE
    }
}
