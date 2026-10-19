package com.corebalt.isp.reporting.infrastructure;

import com.corebalt.isp.reporting.api.DashboardDto;
import com.corebalt.isp.reporting.application.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin dashboard (docs/09 step 8); any staff token, like every other read. */
@RestController
@Tag(name = "Dashboard")
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(final DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/dashboard")
    @Operation(summary = "Lines by status, renewals due and money collected in a period, routers and sessions")
    DashboardDto dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to) {
        return dashboard.dashboard(from, to);
    }
}
