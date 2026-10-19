package com.corebalt.isp.subscriptions.domain;

/** PPPoE usernames (T2): the customer number for the first line, then {@code CB001238-2}, -3, ... */
public final class PppoeUsernames {

    private PppoeUsernames() {}

    /** {@code lineNumber} counts every line the customer ever had, so a username is never reused. */
    public static String forLine(final String customerNumber, final int lineNumber) {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("line numbers start at 1");
        }
        return lineNumber == 1 ? customerNumber : customerNumber + "-" + lineNumber;
    }
}
