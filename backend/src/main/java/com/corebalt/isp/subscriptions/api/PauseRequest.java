package com.corebalt.isp.subscriptions.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Pause until this instant (ISO 8601 UTC); at most the tenant's pause limit (S7). */
public record PauseRequest(@NotNull Instant pauseUntil) {}
