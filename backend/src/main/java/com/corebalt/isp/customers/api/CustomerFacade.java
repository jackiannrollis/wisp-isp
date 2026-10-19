package com.corebalt.isp.customers.api;

import java.util.Collection;
import java.util.List;
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

    /** The customer's phone in E.164, for an STK Push to their own number. */
    Optional<String> phoneOf(UUID customerId);

    /** Exact customer number, e.g. {@code CB001238} (P3 step 1). */
    Optional<CustomerSummary> findByNumber(String number);

    /** Customers with this E.164 phone (P3 step 3; matched only when exactly one). */
    List<CustomerSummary> findByPhone(String e164);

    /** Customers whose phone hashes to this SHA-256 of the 2547... form, lower-case hex (P3 step 3). */
    List<CustomerSummary> findByPhoneHash(String sha256Hex);

    /**
     * Customers whose phone, in 2547... form, starts with {@code prefix} and ends with
     * {@code suffix}. Only a shortlist for staff assigning a masked-number payment; never used to
     * match automatically (P3).
     */
    List<CustomerSummary> findByPhoneDigits(String prefix, String suffix);

    /** Which of these E.164 numbers already belong to one of the tenant's customers. */
    Set<String> existingPhones(Collection<String> phones);
}
