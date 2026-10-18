/**
 * payments: M-Pesa Daraja, STK Push, suspense queue, reconciliation and manual payments.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "payments",
        allowedDependencies = {
                "shared",
                "tenancy :: api",
                "customers :: api"
        })
package com.corebalt.isp.payments;

import org.springframework.modulith.ApplicationModule;
