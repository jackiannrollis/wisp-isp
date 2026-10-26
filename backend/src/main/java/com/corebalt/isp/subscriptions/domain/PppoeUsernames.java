package com.corebalt.isp.subscriptions.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PPPoE usernames (T2): the customer number for the first line, then {@code CB001238-2}, -3, ...
 * The cutover import may instead keep the name a customer had in the ISP's old system (ADR 0014):
 * unique within the tenant, never in the generated shape, so the two namespaces never meet.
 */
public final class PppoeUsernames {

    /** rlm_sql's safe characters without space, colon and slash; RouterOS allows 64. */
    public static final Pattern KEPT_SHAPE = Pattern.compile("[A-Za-z0-9@._-]{1,64}");

    public static final String KEPT_RULE = "1 to 64 letters, digits or @ . _ -";

    private static final Pattern GENERATED_SHAPE = Pattern.compile("[A-Z]{2,4}[0-9]{6}(-[0-9]+)?");

    private PppoeUsernames() {}

    /** {@code lineNumber} counts every line the customer ever had, so a username is never reused. */
    public static String forLine(final String customerNumber, final int lineNumber) {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("line numbers start at 1");
        }
        return lineNumber == 1 ? customerNumber : customerNumber + "-" + lineNumber;
    }

    /** Why {@code username} cannot be kept from an old system, or empty when it can. Case is kept: logins compare it. */
    public static Optional<String> keptProblem(final String username) {
        if (username == null || !KEPT_SHAPE.matcher(username).matches()) {
            return Optional.of(KEPT_RULE);
        }
        if (GENERATED_SHAPE.matcher(username).matches()) {
            return Optional.of("looks like a platform-generated username (T2); a kept name must not");
        }
        return Optional.empty();
    }
}
