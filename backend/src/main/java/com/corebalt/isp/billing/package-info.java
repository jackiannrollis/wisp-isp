/**
 * billing: Wallet, ledger, receipts, invoices, arrears and credit notes.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "billing",
        allowedDependencies = {
                "shared",
                "tenancy :: api",
                "customers :: api",
                "payments :: api"
        })
package com.corebalt.isp.billing;

import org.springframework.modulith.ApplicationModule;
