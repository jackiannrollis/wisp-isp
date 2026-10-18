/**
 * Shared kernel used by every module: {@code Money}, tenant context, UUIDv7 IDs, the transactional
 * outbox, web error handling and security plumbing. No business rules live here.
 *
 * <p>Declared OPEN so every module may use all of its packages.
 */
@ApplicationModule(displayName = "shared", type = ApplicationModule.Type.OPEN)
package com.corebalt.isp.shared;

import org.springframework.modulith.ApplicationModule;
