package com.corebalt.isp.payments.api;

import java.util.UUID;

/**
 * Port for whether a payment reached the ledger, implemented by the billing module (billing already
 * depends on payments, so payments may not call billing; conventions.md, Backend). The
 * reconciliation report lists matched payments for which this is false.
 */
public interface LedgerCreditCheck {

    boolean isCredited(UUID paymentId);
}
