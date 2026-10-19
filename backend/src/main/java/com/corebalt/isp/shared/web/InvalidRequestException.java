package com.corebalt.isp.shared.web;

import java.util.List;

/**
 * Input that passed Bean Validation but fails a domain check (conventions.md, Validation), answered
 * like a validation error: 400 with one entry per offending field. CSV imports use {@code row}.
 */
public class InvalidRequestException extends RuntimeException {

    private final List<Violation> violations;

    public InvalidRequestException(final List<Violation> violations) {
        super(violations.size() + " invalid field(s)");
        this.violations = List.copyOf(violations);
    }

    public static InvalidRequestException of(final String field, final String message) {
        return new InvalidRequestException(List.of(new Violation(null, field, message)));
    }

    public List<Violation> violations() {
        return violations;
    }

    /** {@code row} is the 1-based data row of an import (the header is row 0), otherwise null. */
    public record Violation(Integer row, String field, String message) {}
}
