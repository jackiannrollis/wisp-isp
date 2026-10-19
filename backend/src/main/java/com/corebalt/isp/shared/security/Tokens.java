package com.corebalt.isp.shared.security;

/**
 * Claims of the platform's access tokens (ADR 0010). {@code typ} says who the token is for and
 * decides which API it may call: STAFF tokens {@code /api/v1/**}, CUSTOMER tokens
 * {@code /api/v1/portal/**}, PLATFORM tokens {@code /api/platform/**}. A CHALLENGE token (a step of
 * a login: password change, TOTP, PIN setup) is accepted by no API. Tenant, role and permissions
 * come only from the token, never from a header or query parameter (T1).
 */
public final class Tokens {

    public static final String ISSUER = "corebalt-isp";
    public static final String AUDIENCE = "corebalt-isp-api";

    public static final String TYPE = "typ";
    public static final String TENANT = "tenant_id";
    public static final String ROLE = "role";
    public static final String PERMISSIONS = Permissions.CLAIM;
    public static final String VERSION = "ver";
    /** RFC 8693 actor: the platform admin behind an impersonation token ({@code act.sub}). */
    public static final String ACTOR = "act";

    public static final String STAFF = "STAFF";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String PLATFORM = "PLATFORM";
    public static final String CHALLENGE = "CHALLENGE";

    /** Authority granted per token type, checked by the security chains. */
    public static String typeAuthority(final String type) {
        return "TYPE_" + type;
    }

    private Tokens() {}
}
