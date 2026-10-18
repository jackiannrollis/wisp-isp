/**
 * network: Routers, sites, IP pools, RADIUS entitlement views, CoA, sessions, accounting and usage.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "network",
        allowedDependencies = {
                "shared",
                "tenancy :: api",
                "customers :: api",
                "packages :: api",
                "subscriptions :: api"
        })
package com.corebalt.isp.network;

import org.springframework.modulith.ApplicationModule;
