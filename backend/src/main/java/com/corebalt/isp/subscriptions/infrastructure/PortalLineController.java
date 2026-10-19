package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.subscriptions.api.PortalLineDto;
import com.corebalt.isp.subscriptions.application.LineStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The customer's own lines (docs/09 portal: status, package, expiry); CUSTOMER tokens only. */
@RestController
@Tag(name = "Portal")
class PortalLineController {

    private final LineStatisticsService lines;

    PortalLineController(final LineStatisticsService lines) {
        this.lines = lines;
    }

    @GetMapping("/api/v1/portal/lines")
    @Operation(summary = "My lines with status, package, expiry and what the next renewal needs")
    List<PortalLineDto> lines() {
        return lines.portalLines(CurrentActor.requireCustomerId());
    }
}
