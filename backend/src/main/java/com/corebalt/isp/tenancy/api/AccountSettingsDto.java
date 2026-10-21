package com.corebalt.isp.tenancy.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The account's own policy and look (settings.write; decisions.md 2026-10-21). {@code code},
 * {@code timeZone} and {@code currency} are fixed when the tenant is created and only shown.
 * Grace and the partial-payment rule apply from each line's next renewal (S6); the pause limit to the
 * next pause; branding at once.
 */
public record AccountSettingsDto(
        String code,
        String name,
        String brandColor,
        String logoUrl,
        String timeZone,
        String currency,
        int defaultGraceDays,
        String graceMode,
        String partialPaymentRule,
        int maxPauseDays) {

    /** What staff may change. The logo is an https address; null removes it. */
    @Schema(name = "AccountSettingsRequest")
    public record Request(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "#[0-9a-fA-F]{6}") String brandColor,
            @Size(max = 500) @Pattern(regexp = "https://\\S+") String logoUrl,
            @NotNull @Min(0) @Max(30) Integer defaultGraceDays,
            @NotNull @Pattern(regexp = "FULL_SPEED|THROTTLED") String graceMode,
            @NotNull @Pattern(regexp = "WAIT_FOR_FULL|PRO_RATA_DAYS") String partialPaymentRule,
            @NotNull @Min(1) @Max(365) Integer maxPauseDays) {}
}
