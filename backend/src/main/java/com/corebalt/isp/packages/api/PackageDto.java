package com.corebalt.isp.packages.api;

import com.corebalt.isp.shared.money.MoneyDto;
import java.time.Instant;
import java.util.UUID;

public record PackageDto(
        UUID id,
        String name,
        String type,
        int downloadKbps,
        int uploadKbps,
        int durationMinutes,
        MoneyDto price,
        Integer graceDays,
        Long dataCapBytes,
        Long fupBytes,
        Integer throttleDownloadKbps,
        Integer throttleUploadKbps,
        Integer deviceLimit,
        String billingMode,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
