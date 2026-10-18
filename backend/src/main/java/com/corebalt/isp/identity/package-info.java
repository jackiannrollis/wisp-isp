/**
 * identity: Staff users, roles, permissions, 2FA, audit log and portal logins.
 *
 * <p>See docs/02-architecture.md for what this module owns, publishes and consumes.
 */
@ApplicationModule(
        displayName = "identity",
        allowedDependencies = {
                "shared",
                "tenancy :: api"
        })
package com.corebalt.isp.identity;

import org.springframework.modulith.ApplicationModule;
