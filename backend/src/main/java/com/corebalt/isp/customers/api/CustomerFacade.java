package com.corebalt.isp.customers.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The customers module's interface for other modules; always scoped to the current tenant (T1). */
public interface CustomerFacade {

    Optional<CustomerSummary> find(UUID customerId);

    /** Registers a customer with the next number (T2) and publishes {@link CustomerCreated}. */
    CustomerDto register(CustomerRequest request);

    /** The E.164 form of {@code phone}, or empty when it is not a valid number. */
    Optional<String> normalisePhone(String phone);

    /** Which of these E.164 numbers already belong to one of the tenant's customers. */
    Set<String> existingPhones(Collection<String> phones);
}
