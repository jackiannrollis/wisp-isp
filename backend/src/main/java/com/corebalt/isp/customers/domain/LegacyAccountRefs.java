package com.corebalt.isp.customers.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The account reference a customer used in the ISP's old system (ADR 0014, P3 step 2). Payers keep
 * typing it for months after a cutover, so it is matched exactly: stored trimmed and upper-cased,
 * compared the same way. Letters, digits, spaces and {@code . _ / # -}, 1 to 32 characters.
 */
public final class LegacyAccountRefs {

    public static final String RULE = "1 to 32 letters, digits, spaces or . _ / # -";

    private static final Pattern SHAPE = Pattern.compile("[A-Z0-9][A-Z0-9 ._/#-]{0,31}");

    private LegacyAccountRefs() {}

    /**
     * The stored form of {@code raw}: empty for a blank (the customer has none), else trimmed and
     * upper-cased. A value outside the shape is an {@link IllegalArgumentException} with {@link #RULE}.
     */
    public static Optional<String> normalise(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        final String value = raw.strip().toUpperCase(Locale.ROOT);
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(RULE);
        }
        return Optional.of(value);
    }

    /**
     * Whether {@code reference} (normalised) is 1 to 6 digits: the shape P3's normalisation reads as
     * a short customer number, so such a reference shadows that number's short form.
     */
    public static boolean looksLikeShortCustomerNumber(final String reference) {
        return reference != null && reference.matches("[0-9]{1,6}");
    }
}
