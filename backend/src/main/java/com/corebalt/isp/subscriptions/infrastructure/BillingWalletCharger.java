package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.billing.api.Wallets;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.subscriptions.api.WalletCharger;
import java.util.Currency;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link WalletCharger} on billing's wallet and ledger. */
@Component
class BillingWalletCharger implements WalletCharger {

    private final Wallets wallets;

    BillingWalletCharger(final Wallets wallets) {
        this.wallets = wallets;
    }

    @Override
    public Money balance(final UUID customerId, final Currency currency) {
        return wallets.balance(customerId, currency);
    }

    @Override
    public Outcome charge(
            final UUID customerId, final Money amount, final Kind kind, final UUID referenceId, final String description) {
        final Wallets.ChargeType type =
                kind == Kind.RENEWAL ? Wallets.ChargeType.RENEWAL_CHARGE : Wallets.ChargeType.PRORATION_CHARGE;
        return switch (wallets.charge(customerId, amount, type, referenceId, description)) {
            case CHARGED -> Outcome.CHARGED;
            case ALREADY_CHARGED -> Outcome.ALREADY_CHARGED;
            case INSUFFICIENT_BALANCE -> Outcome.INSUFFICIENT_BALANCE;
        };
    }
}
