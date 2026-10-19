/**
 * subscriptions: Subscriptions, expiry, lifecycle states, renewals, proration and pause.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "subscriptions",
        allowedDependencies = {
                "shared",
                "tenancy :: api",
                "identity :: api",
                "customers :: api",
                "packages :: api",
                "billing :: api"
        })
package com.corebalt.isp.subscriptions;

import org.springframework.modulith.ApplicationModule;
