/**
 * packages: Package definitions and prices.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "packages",
        allowedDependencies = {
                "shared",
                "tenancy :: api"
        })
package com.corebalt.isp.packages;

import org.springframework.modulith.ApplicationModule;
