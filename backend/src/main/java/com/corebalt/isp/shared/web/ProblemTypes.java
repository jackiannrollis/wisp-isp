package com.corebalt.isp.shared.web;

import java.net.URI;

/** Stable RFC 9457 problem {@code type} URIs; clients may switch on them, so never rename one. */
public final class ProblemTypes {

    private static final String BASE = "https://problems.corebalt.app/";

    public static final URI UNAUTHENTICATED = URI.create(BASE + "unauthenticated");
    public static final URI FORBIDDEN = URI.create(BASE + "forbidden");
    public static final URI TENANT_CONTEXT_MISSING = URI.create(BASE + "tenant-context-missing");
    public static final URI VALIDATION_FAILED = URI.create(BASE + "validation-failed");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE + "idempotency-key-reused");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemTypes() {}
}
