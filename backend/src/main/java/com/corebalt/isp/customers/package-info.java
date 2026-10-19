/**
 * customers: Customer profiles, contact details and location. Lines and their PPPoE credentials belong to
 * subscriptions and network (ADR 0009).
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "customers",
        allowedDependencies = {
                "shared",
                "tenancy :: api"
        })
package com.corebalt.isp.customers;

import org.springframework.modulith.ApplicationModule;
