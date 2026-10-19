package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.money.Money;
import java.util.UUID;

/**
 * Port for charging a renewal to the customer's wallet, implemented by the billing module (step 5)
 * under the wallet row lock (B3, B5), so two renewals cannot both spend one payment. Called inside
 * the renewal's transaction: a charge and its renewal commit or roll back together. The balance is
 * never taken from an event. {@code reference} identifies the renewal so billing can refuse to
 * charge it twice.
 */
public interface WalletCharger {

    Outcome charge(UUID customerId, Money amount, String reference);

    enum Outcome {
        CHARGED,
        INSUFFICIENT_BALANCE
    }
}
