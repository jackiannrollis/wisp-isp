package com.corebalt.isp.reporting.infrastructure;

import com.corebalt.isp.reporting.api.PaidOfflineReportDto;
import com.corebalt.isp.reporting.application.ManualReconnects;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Support's "Paid but still offline" button on the customer page (ADR 0018); customer care, so `customers.write`. */
@RestController
@Tag(name = "Subscriptions")
class PaidOfflineController {

    record ReportRequest(@Size(max = 500) String note) {}

    private final ManualReconnects reconnects;

    PaidOfflineController(final ManualReconnects reconnects) {
        this.reconnects = reconnects;
    }

    @PostMapping("/api/v1/subscriptions/{id}/paid-offline-reports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('customers.write')")
    @Operation(summary = "Record that the customer paid but the line is still offline: an incident linked to the customer's "
            + "latest payment, counted by Gate 1 as a manual reconnect (409 when the customer has never paid)")
    PaidOfflineReportDto report(@PathVariable final UUID id, @Valid @RequestBody(required = false) final ReportRequest request) {
        final String note = request == null || request.note() == null || request.note().isBlank() ? null : request.note().strip();
        return reconnects.reportPaidOffline(id, note);
    }
}
