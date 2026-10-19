package com.corebalt.isp.billing.infrastructure;

import com.corebalt.isp.payments.api.LedgerCreditCheck;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Billing's answer to payments' {@link LedgerCreditCheck}: is there a PAYMENT entry for it? */
@Component
class LedgerCreditCheckAdapter implements LedgerCreditCheck {

    private final Ledger ledger;

    LedgerCreditCheckAdapter(final Ledger ledger) {
        this.ledger = ledger;
    }

    @Override
    public boolean isCredited(final UUID paymentId) {
        return ledger.hasEntryFor("PAYMENT", paymentId);
    }
}
