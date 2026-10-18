package com.corebalt.isp.tenancy.infrastructure;

import com.corebalt.isp.tenancy.api.BrandingDto;
import com.corebalt.isp.tenancy.application.BrandingQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stub endpoint the web apps load their theme from; full tenant settings come later. */
@RestController
@RequestMapping("/api/v1/branding")
@Tag(name = "Tenancy")
class BrandingController {

    private final BrandingQuery brandingQuery;

    BrandingController(final BrandingQuery brandingQuery) {
        this.brandingQuery = brandingQuery;
    }

    @GetMapping
    @Operation(summary = "Branding of the caller's tenant")
    BrandingDto branding() {
        return brandingQuery.currentTenantBranding();
    }
}
