package com.corebalt.isp.shared.web;

/**
 * A request that is valid but conflicts with the current state, answered with 409. The message is
 * shown to the caller, so it must name the rule (domain-rules.md ID) and never carry secrets or SQL.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(final String message) {
        super(message);
    }
}
