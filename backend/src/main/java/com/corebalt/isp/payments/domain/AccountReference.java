package com.corebalt.isp.payments.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * P3 step 2: the account reference a payer typed, normalised to a customer number. Trim, upper-case,
 * drop spaces, dots and hyphens between the tenant code and the digits, ignore a line suffix
 * ({@code CB001238-2} pays customer {@code CB001238}), and tolerate a missing tenant prefix by
 * left-padding up to six digits ({@code 1238} is {@code CB001238}). Never a fuzzy name match.
 */
public final class AccountReference {

    private AccountReference() {}

    public static Optional<String> normalise(final String raw, final String tenantCode) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s.]", "");
        // A line username: the customer number before the suffix.
        if (value.matches("[A-Z]{2,4}-?[0-9]{1,6}-[0-9]+")) {
            value = value.substring(0, value.lastIndexOf('-'));
        }
        value = value.replace("-", "");
        if (value.matches("[0-9]{1,6}")) {
            return Optional.of(tenantCode + "0".repeat(6 - value.length()) + value);
        }
        if (value.startsWith(tenantCode) && value.substring(tenantCode.length()).matches("[0-9]{1,6}")) {
            final String digits = value.substring(tenantCode.length());
            return Optional.of(tenantCode + "0".repeat(6 - digits.length()) + digits);
        }
        return Optional.empty();
    }
}
