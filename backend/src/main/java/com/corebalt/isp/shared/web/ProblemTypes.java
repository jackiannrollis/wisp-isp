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
    public static final URI SERVICE_UNAVAILABLE = URI.create(BASE + "service-unavailable");
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE + "idempotency-key-reused");
    /** A sign-in step was refused (wrong credentials, expired step); deliberately vague. */
    public static final URI SIGN_IN_FAILED = URI.create(BASE + "sign-in-failed");
    /** Locked out or rate-limited: wait and try again. */
    public static final URI TOO_MANY_ATTEMPTS = URI.create(BASE + "too-many-attempts");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemTypes() {}
}
