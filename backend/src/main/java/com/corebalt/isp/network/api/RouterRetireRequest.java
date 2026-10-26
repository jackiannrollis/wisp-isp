package com.corebalt.isp.network.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Retire a router (N10): why, and which active router takes its lines. The replacement is required
 * when the router still has lines that are not terminated; terminated lines stay on it as history.
 */
public record RouterRetireRequest(@NotBlank @Size(max = 500) String reason, UUID replacementRouterId) {}
