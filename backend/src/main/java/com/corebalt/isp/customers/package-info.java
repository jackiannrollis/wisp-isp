/**
 * customers: Customer profiles, PPPoE credentials, router and MAC, location.
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
