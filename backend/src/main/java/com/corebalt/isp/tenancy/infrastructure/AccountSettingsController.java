package com.corebalt.isp.tenancy.infrastructure;

import com.corebalt.isp.tenancy.api.AccountSettingsDto;
import com.corebalt.isp.tenancy.application.AccountSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The account's own policy and look (settings.write, Owner only; decisions.md 2026-10-21). */
@RestController
@RequestMapping("/api/v1/settings/account")
@Tag(name = "Settings")
@PreAuthorize("hasAuthority('settings.write')")
class AccountSettingsController {

    private final AccountSettings settings;

    AccountSettingsController(final AccountSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    @Operation(summary = "Grace policy, partial-payment rule, pause limit and branding of the account")
    AccountSettingsDto view() {
        return settings.view();
    }

    @PutMapping
    @Operation(summary = "Change them: grace and the partial-payment rule apply from each line's next renewal (S6), "
            + "the pause limit to the next pause, branding at once")
    AccountSettingsDto update(@Valid @RequestBody final AccountSettingsDto.Request request) {
        return settings.update(request);
    }
}
