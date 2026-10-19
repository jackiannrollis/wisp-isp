package com.corebalt.isp.customers.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalises phone numbers to E.164 ({@code +254712345678}). Tenants are Kenyan ISPs, so national
 * forms ({@code 0712 345 678}, {@code 712345678}, {@code 254712345678}) are read as +254; any other
 * country must be written with its {@code +} prefix.
 */
public final class PhoneNumbers {

    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");

    private PhoneNumbers() {}

    public static Optional<String> normalise(final String input) {
        if (input == null) {
            return Optional.empty();
        }
        final String digits = SEPARATORS.matcher(input.strip()).replaceAll("");
        final String candidate;
        if (digits.startsWith("+")) {
            candidate = digits;
        } else if (digits.matches("254[17][0-9]{8}")) {
            candidate = "+" + digits;
        } else if (digits.matches("0[17][0-9]{8}")) {
            candidate = "+254" + digits.substring(1);
        } else if (digits.matches("[17][0-9]{8}")) {
            candidate = "+254" + digits;
        } else {
            return Optional.empty();
        }
        return E164.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }
}
