package com.corebalt.isp.payments.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments that arrived by M-Pesa callback (C2B confirmation, STK result, Transaction Status), for
 * Gate 1 (docs/09, ADR 0018); current tenant only. Receipts entered by hand are not among them, even on
 * the M-Pesa channel: no callback created them.
 */
public interface CallbackPayments {

    /**
     * When the platform first recorded a callback for the payment (not M-Pesa's own transaction time);
     * empty for a payment no callback created.
     */
    Optional<Instant> firstCallbackAt(UUID paymentId);

    /** How the callback payments recorded in [from, to) were matched (P3), reversed ones left out. */
    Matching matching(Instant from, Instant to);

    /**
     * {@code automatic}: matched on arrival, by the account reference or the payer's number, or by the
     * STK request; {@code byStaff}: assigned from the suspense queue; {@code unmatched}: still there.
     */
    record Matching(long automatic, long byStaff, long unmatched) {

        public long total() {
            return automatic + byStaff + unmatched;
        }
    }
}
