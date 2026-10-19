package com.corebalt.isp.packages.api;

import com.corebalt.isp.shared.money.MoneyDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Create or revise a package. Speeds in kbit/s ({@code 10000} is 10 Mbit/s); throttle speeds come as
 * a pair or not at all. {@code type} and {@code billingMode} default to PPPOE and PREPAID; the type
 * cannot change once created.
 */
public record PackageRequest(
        @NotBlank @Size(max = 100) String name,
        String type,
        @NotNull @Positive @Max(10_000_000) Integer downloadKbps,
        @NotNull @Positive @Max(10_000_000) Integer uploadKbps,
        @NotNull @Positive Integer durationMinutes,
        @NotNull @Valid MoneyDto price,
        @Min(0) @Max(365) Integer graceDays,
        @Positive Long dataCapBytes,
        @Positive Long fupBytes,
        @Positive @Max(10_000_000) Integer throttleDownloadKbps,
        @Positive @Max(10_000_000) Integer throttleUploadKbps,
        @Positive Integer deviceLimit,
        String billingMode) {}
