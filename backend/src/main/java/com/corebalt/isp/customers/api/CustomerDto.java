package com.corebalt.isp.customers.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerDto(
        UUID id,
        String number,
        String name,
        String phone,
        String email,
        String address,
        String installationLocation,
        BigDecimal lat,
        BigDecimal lng,
        String status,
        Instant createdAt,
        Instant updatedAt) {}
