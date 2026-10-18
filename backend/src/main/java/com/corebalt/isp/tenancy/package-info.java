/**
 * tenancy: Tenants, settings, branding and encrypted secrets.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "tenancy",
        allowedDependencies = {
                "shared"
        })
package com.corebalt.isp.tenancy;

import org.springframework.modulith.ApplicationModule;
