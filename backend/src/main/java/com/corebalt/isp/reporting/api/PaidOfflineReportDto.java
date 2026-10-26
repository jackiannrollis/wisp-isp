package com.corebalt.isp.reporting.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Support's "Paid but still offline" on a line (ADR 0018): counted by Gate 1 as a manual reconnect, linked
 * to the customer's latest payment (its time only: amounts need finance.read).
 */
public record PaidOfflineReportDto(UUID id, UUID subscriptionId, UUID paymentId, Instant paymentMatchedAt,
                                   Instant reportedAt) {}
