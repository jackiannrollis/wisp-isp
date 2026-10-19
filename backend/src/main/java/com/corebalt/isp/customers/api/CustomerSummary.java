package com.corebalt.isp.customers.api;

import java.util.UUID;

/** What other modules may know about a customer. */
public record CustomerSummary(UUID id, String number, String name, boolean active) {}
