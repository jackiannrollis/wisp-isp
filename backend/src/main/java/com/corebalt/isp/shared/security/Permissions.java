package com.corebalt.isp.shared.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Permission codes (A2): fixed strings, granted through a tenant's roles, carried in the access
 * token and checked server-side with {@code @PreAuthorize}. Every write needs a code; any staff
 * member may read, except the audit log, staff management and money ({@link #FINANCE_READ}).
 */
public final class Permissions {

    public static final String CLAIM = "permissions";

    public static final String CUSTOMERS_WRITE = "customers.write";
    public static final String PACKAGES_WRITE = "packages.write";
    /** Open lines, lifecycle transitions, package changes, PPPoE password regeneration. */
    public static final String SUBSCRIPTIONS_WRITE = "subscriptions.write";
    /**
     * S11: import lines already active, with the expiry their customers paid for in the ISP's old
     * system, while the tenant's cutover window is open (ADR 0014); correct a kept PPPoE username.
     * Held by the built-in Owner role only, and 2FA.
     */
    public static final String SUBSCRIPTIONS_IMPORT_ACTIVE = "subscriptions.import_active";
    /** Register routers and regenerate their secrets. */
    public static final String NETWORK_WRITE = "network.write";
    /** P5: record a cash, bank or M-Pesa payment by hand. */
    public static final String PAYMENTS_RECORD_MANUAL = "payments.record_manual";
    /** P3: assign an unmatched payment from the suspense queue to a customer. */
    public static final String PAYMENTS_ASSIGN = "payments.assign";
    /** Start an M-Pesa STK Push for a customer. */
    public static final String PAYMENTS_STK = "payments.stk";
    /** Upload M-Pesa statements and run Transaction Status investigations. */
    public static final String PAYMENTS_RECONCILE = "payments.reconcile";
    /** B1: post a wallet ADJUSTMENT with a reason (for example free days). */
    public static final String BILLING_ADJUST = "billing.adjust";
    /** T4: set or replace tenant secrets (M-Pesa, SMS) and the settings that use them. */
    public static final String SETTINGS_SECRETS = "settings.secrets";
    /** M2, M3: edit SMS templates and the sending window. */
    public static final String SETTINGS_NOTIFICATIONS = "settings.notifications";
    /**
     * The account's own policy and look: grace policy, partial-payment rule, pause limit, branding, SMS
     * sender ID and sending window (decisions.md 2026-10-21). Held by the built-in Owner role only.
     */
    public static final String SETTINGS_WRITE = "settings.write";
    /** Invite, disable and re-role staff; edit roles. */
    public static final String STAFF_MANAGE = "staff.manage";
    /** A1: read the audit log. */
    public static final String AUDIT_READ = "audit.read";
    /**
     * See money: the dashboard's revenue figures, wallet balances, statements, receipts and payments.
     * Without it the dashboard leaves its revenue block out rather than refusing.
     */
    public static final String FINANCE_READ = "finance.read";

    /** The whole catalogue, for the role editor and the built-in Owner role. */
    public static final List<String> ALL = List.of(
            CUSTOMERS_WRITE, PACKAGES_WRITE, SUBSCRIPTIONS_WRITE, SUBSCRIPTIONS_IMPORT_ACTIVE, NETWORK_WRITE,
            PAYMENTS_RECORD_MANUAL, PAYMENTS_ASSIGN,
            PAYMENTS_STK, PAYMENTS_RECONCILE, BILLING_ADJUST, SETTINGS_SECRETS, SETTINGS_NOTIFICATIONS, SETTINGS_WRITE,
            STAFF_MANAGE, AUDIT_READ, FINANCE_READ);

    private Permissions() {}

    /** Authorities of a verified token: its type, then (staff only) its permission codes. */
    static AbstractAuthenticationToken authentication(final Jwt jwt) {
        final Collection<GrantedAuthority> authorities = new ArrayList<>();
        final String type = jwt.getClaimAsString(Tokens.TYPE);
        if (type != null) {
            authorities.add(new SimpleGrantedAuthority(Tokens.typeAuthority(type)));
        }
        final List<String> codes = jwt.getClaimAsStringList(CLAIM);
        if (codes != null && Tokens.STAFF.equals(type)) {
            codes.stream().filter(ALL::contains).map(SimpleGrantedAuthority::new).forEach(authorities::add);
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
