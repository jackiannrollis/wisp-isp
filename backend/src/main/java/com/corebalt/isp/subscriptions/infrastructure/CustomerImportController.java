package com.corebalt.isp.subscriptions.infrastructure;

import com.corebalt.isp.subscriptions.api.CustomerImportResultDto;
import com.corebalt.isp.subscriptions.application.CustomerImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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

    private final CustomerImportService service;

    CustomerImportController(final CustomerImportService service) {
        this.service = service;
    }

    @PostMapping(consumes = {"text/csv", "text/plain"})
    @Operation(
            summary = "Import customers from CSV, all rows or none",
            description = "Columns: name, phone (required), email, address, installation_location, lat, lng, package."
                    + " Rows with a package get a PENDING line on routerId; their PPPoE passwords are in this"
                    + " response only.")
    ResponseEntity<CustomerImportResultDto> importCsv(
            @RequestBody final String csv, @RequestParam(required = false) final UUID routerId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.importCsv(csv, routerId));
    }
}
