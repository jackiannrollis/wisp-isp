package com.corebalt.isp.customers.infrastructure;

import com.corebalt.isp.customers.domain.Customer;
import com.corebalt.isp.customers.domain.CustomerStatus;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** List filters for the customer table; each is null-safe so callers can combine them freely. */
public final class CustomerSpecifications {

    private CustomerSpecifications() {}

    /** Name contains, number starts with, or (three digits or more) phone contains the query. */
    public static Specification<Customer> matching(final String query) {
        if (query == null || query.isBlank()) {
            return (root, cq, cb) -> null;
        }
        final String text = query.strip().toLowerCase(Locale.ROOT);
        // Phones are stored in E.164, so a national trunk 0 (0733...) is dropped before matching.
        final String allDigits = text.replaceAll("[^0-9]", "");
        final String digits = allDigits.startsWith("0") ? allDigits.substring(1) : allDigits;
        return (root, cq, cb) -> {
            final var byName = cb.like(cb.lower(root.get("name")), "%" + escape(text) + "%", '\\');
            final var byNumber = cb.like(cb.lower(root.get("number")), escape(text) + "%", '\\');
            if (digits.length() < 3) {
                return cb.or(byName, byNumber);
            }
            return cb.or(byName, byNumber, cb.like(root.get("phone"), "%" + digits + "%"));
        };
    }

    public static Specification<Customer> withStatus(final CustomerStatus status) {
        return status == null
                ? (root, cq, cb) -> null
                : (root, cq, cb) -> cb.equal(root.get("status"), status);
    }

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
