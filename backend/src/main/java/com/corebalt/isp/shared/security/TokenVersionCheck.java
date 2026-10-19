package com.corebalt.isp.shared.security;

import java.util.Map;

/**
 * Whether an access token is still good (ADR 0010): every user has a token version, bumped when the
 * user is disabled, changes a password or PIN, or changes role; a token carrying an older version,
 * or an impersonation that has been ended, is refused on the next request, not when it expires.
 * Implemented by the identity module.
 */
public interface TokenVersionCheck {

    boolean isCurrent(Map<String, Object> claims);
}
