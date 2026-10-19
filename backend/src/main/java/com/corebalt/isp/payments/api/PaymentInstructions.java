package com.corebalt.isp.payments.api;

import java.util.Optional;
import java.util.UUID;

/** What customer messages need from payments; current tenant only. Never secrets. */
public interface PaymentInstructions {

    /** The tenant's Paybill or Till number, when M-Pesa is set up. */
    Optional<String> shortcode();

    /**
     * An UNMATCHED payment, for the "reference not recognised" SMS. {@code plainPhone} is the
     * payer's E.164 number only when Daraja sent it unmasked; a masked or hashed number cannot be
     * messaged.
     */
    Optional<Unmatched> unmatched(UUID paymentId);

    record Unmatched(long amountMinor, String accountRef, Optional<String> plainPhone) {}
}
