package com.corebalt.isp.customers.infrastructure;

import com.corebalt.isp.customers.api.CustomerDto;
import com.corebalt.isp.customers.application.CustomerService;
import com.corebalt.isp.shared.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's own record (docs/09 portal); read-only. */
@RestController
@Tag(name = "Portal")
class PortalCustomerController {

    private final CustomerService customers;

    PortalCustomerController(final CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/api/v1/portal/customer")
    @Operation(summary = "My customer number, name and contact details")
    CustomerDto me() {
        return customers.get(CurrentActor.requireCustomerId());
    }
}
