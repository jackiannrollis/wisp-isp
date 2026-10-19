package com.corebalt.isp.billing.api;

import java.util.Optional;
import java.util.UUID;

/** Receipt numbers for other modules (the payment SMS quotes it, docs/05); current tenant only. */
public interface Receipts {

    Optional<String> numberForPayment(UUID paymentId);
}
