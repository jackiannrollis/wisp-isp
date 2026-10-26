/**
 * reporting: Dashboard counts and revenue and usage reports, read from the replica; Gate 1 measured
 * per tenant (restores, matching, reconnects needing hands; ADR 0018).
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "reporting",
        allowedDependencies = {
                "shared",
                "tenancy :: api",
                "customers :: api",
                "packages :: api",
                "subscriptions :: api",
                "billing :: api",
                "payments :: api",
                "network :: api",
                "notifications :: api"
        })
package com.corebalt.isp.reporting;

import org.springframework.modulith.ApplicationModule;
