package com.corebalt.isp.shared.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Permission codes (A2): fixed strings checked server-side with {@code @PreAuthorize}, carried in
 * the staff JWT's {@code permissions} claim. Roles that bundle them, and the tokens themselves, come
 * with the identity module; until then only money endpoints check a code.
 */
public final class Permissions {

    public static final String CLAIM = "permissions";

    /** P5: record a cash, bank or M-Pesa payment by hand. */
    public static final String PAYMENTS_RECORD_MANUAL = "payments.record_manual";

    /** B1: post a wallet ADJUSTMENT with a reason (for example free days). */
    public static final String BILLING_ADJUST = "billing.adjust";

    /** T4: set or replace tenant secrets (M-Pesa, SMS) and the M-Pesa settings that use them. */
    public static final String SETTINGS_SECRETS = "settings.secrets";

    /** P3: assign an unmatched payment from the suspense queue to a customer. */
    public static final String PAYMENTS_ASSIGN = "payments.assign";

    /** Start an M-Pesa STK Push for a customer. */
    public static final String PAYMENTS_STK = "payments.stk";

    /** Upload M-Pesa statements and run Transaction Status investigations. */
    public static final String PAYMENTS_RECONCILE = "payments.reconcile";

    private Permissions() {}

    static AbstractAuthenticationToken authentication(final Jwt jwt) {
        final List<String> codes = jwt.getClaimAsStringList(CLAIM);
        final Collection<GrantedAuthority> authorities =
                codes == null ? List.of() : codes.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
