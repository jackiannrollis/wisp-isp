package com.corebalt.isp.subscriptions.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why staff took an action; kept in the audit log (A1). */
public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}
