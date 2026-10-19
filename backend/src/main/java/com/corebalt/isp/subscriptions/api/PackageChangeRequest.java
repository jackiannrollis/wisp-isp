package com.corebalt.isp.subscriptions.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** {@code timing}: NOW (upgrades only, charged by proration) or NEXT_RENEWAL (S10). */
public record PackageChangeRequest(@NotNull UUID packageId, @NotNull @Pattern(regexp = "NOW|NEXT_RENEWAL") String timing) {}
