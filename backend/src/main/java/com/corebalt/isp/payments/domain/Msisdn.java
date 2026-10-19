package com.corebalt.isp.payments.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The payer's number as Daraja sends it in a C2B confirmation (P3): a plain number, a SHA-256 hash
 * of the 2547... form (some v1 shortcodes), or masked like {@code 2547 ***** 126} (v2). Only plain
 * and hashed numbers may be matched automatically; a masked one only yields a staff shortlist.
 */
public sealed interface Msisdn {

    /** {@code e164} like {@code +254712345678}. */
    record Plain(String e164) implements Msisdn {}

    /** Lower-case hex SHA-256 of the 2547... form. */
    record Hashed(String sha256Hex) implements Msisdn {}

    /** The visible digits around the mask, e.g. prefix {@code 2547}, suffix {@code 126}. */
    record Masked(String prefix, String suffix) implements Msisdn {}

    record Absent() implements Msisdn {}

    Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    Pattern MASKED = Pattern.compile("([0-9]*)\\s*\\*+\\s*([0-9]*)");

    static Msisdn parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return new Absent();
        }
        final String value = raw.strip();
        if (HASH.matcher(value).matches()) {
            return new Hashed(value.toLowerCase(Locale.ROOT));
        }
        final Matcher masked = MASKED.matcher(value.replace(" ", ""));
        if (value.contains("*") && masked.matches()) {
            return new Masked(masked.group(1), masked.group(2));
        }
        final String digits = value.replaceAll("[\\s+-]", "");
        if (digits.matches("254[17][0-9]{8}")) {
            return new Plain("+" + digits);
        }
        if (digits.matches("0[17][0-9]{8}")) {
            return new Plain("+254" + digits.substring(1));
        }
        return new Absent();
    }

    /** For staff screens and logs: never the full number (A3). */
    static String display(final String raw) {
        return switch (parse(raw)) {
            case Plain p -> p.e164().substring(0, 7) + "***" + p.e164().substring(p.e164().length() - 3);
            case Masked m -> m.prefix() + "*****" + m.suffix();
            case Hashed h -> "hashed";
            case Absent a -> "";
        };
    }
}
