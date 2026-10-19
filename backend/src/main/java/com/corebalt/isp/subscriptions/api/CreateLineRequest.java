package com.corebalt.isp.subscriptions.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Open a PPPoE line for a customer on one of the tenant's routers. */
public record CreateLineRequest(@NotNull UUID customerId, @NotNull UUID packageId, @NotNull UUID routerId) {}
