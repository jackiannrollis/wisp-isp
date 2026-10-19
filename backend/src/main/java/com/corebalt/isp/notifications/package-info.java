/**
 * notifications: Templates, SMS dispatch and delivery log.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "notifications",
        allowedDependencies = {
                "shared",
                "tenancy :: api",
                "customers :: api",
                "packages :: api",
                "subscriptions :: api",
                "billing :: api",
                "payments :: api",
                "network :: api"
        })
package com.corebalt.isp.notifications;

import org.springframework.modulith.ApplicationModule;
