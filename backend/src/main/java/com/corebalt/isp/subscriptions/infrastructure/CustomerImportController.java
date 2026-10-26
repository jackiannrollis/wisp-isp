package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.CustomerImportPreviewDto;
import com.corebalt.isp.subscriptions.api.CustomerImportResultDto;
import com.corebalt.isp.subscriptions.api.CustomerImports;
import com.corebalt.isp.subscriptions.application.CustomerImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSV import of customers and their lines. It lives in subscriptions because it creates lines, and
 * subscriptions is the module that may use both customers and packages.
 */
@RestController
@RequestMapping("/api/v1/customer-imports")
@Tag(name = "Customers")
class CustomerImportController {

    private static final String COLUMNS = "Columns: name, phone (required), email, address, installation_location, lat, lng,"
            + " package, paid_until, pppoe_username, pppoe_password, legacy_account_ref.";

    private final CustomerImportService service;
    private final CustomerImports imports;

    CustomerImportController(final CustomerImportService service, final CustomerImports imports) {
        this.service = service;
        this.imports = imports;
    }

    @PostMapping(path = "/preview", consumes = {"text/csv", "text/plain"})
    @PreAuthorize("hasAuthority('customers.write') and hasAuthority('subscriptions.write')")
    @Operation(
            summary = "Dry run of an import: the same validation, nothing written",
            description = COLUMNS + " Returns the totals (lines activating, already expired, in the future; the earliest and"
                    + " latest paid_until; the value of the time granted), warnings that usually mean a swapped date format,"
                    + " and the confirmation an import with paid_until must send back (ADR 0014). timeOfDay (HH:mm, default"
                    + " 12:00, tenant time) is the expiry time of a date-only paid_until.")
    CustomerImportPreviewDto preview(
            @RequestBody final String csv,
            @RequestParam(required = false) final UUID routerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) final LocalTime timeOfDay) {
        return service.preview(csv, routerId, timeOfDay);
    }

    @PostMapping(consumes = {"text/csv", "text/plain"})
    @PreAuthorize("hasAuthority('customers.write') and hasAuthority('subscriptions.write')")
    @Operation(
            summary = "Import customers from CSV, all rows or none",
            description = COLUMNS + " Rows with a package get a line on routerId: PENDING, or ACTIVE until paid_until when the"
                    + " caller holds subscriptions.import_active and the tenant's cutover window is open (S11, ADR 0014), in"
                    + " which case confirmation must be the preview's for this file. Kept PPPoE credentials are never echoed;"
                    + " generated passwords are in this response only.")
    ResponseEntity<CustomerImportResultDto> importCsv(
            @RequestBody final String csv,
            @RequestParam(required = false) final UUID routerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) final LocalTime timeOfDay,
            @RequestParam(required = false) final String confirmation) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.importCsv(csv, routerId, timeOfDay, confirmation));
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "One import batch: the totals its preview showed when it was committed")
    CustomerImports.ImportBatch batch(@PathVariable final UUID batchId) {
        return imports.batch(batchId).orElseThrow(() -> new NotFoundException("Import batch"));
    }
}
