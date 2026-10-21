package com.corebalt.isp.notifications.domain;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMS templates (docs/04 Reminders, docs/05). Each default fits one GSM-7 part (160 characters)
 * with realistic values ({@link #SAMPLE}); a test enforces it. Placeholders come from a fixed list
 * that holds no secrets, PINs or national IDs (M3), so a tenant's own wording cannot leak them.
 * {@code immediate}: sent at once; otherwise held to the tenant's sending window (M2).
 */
public enum Template {
    EXPIRING_3D(false, "Hi {name}, your {package} internet expires on {expires_on}. Pay KES {price} to Paybill {paybill}, account {number}."),
    EXPIRING_3D_AUTO(false, "Hi {name}, your {package} internet renews automatically on {expires_on} from your KES {balance} credit. Account {number}."),
    EXPIRING_1D(false, "Hi {name}, your {package} internet expires tomorrow. Pay KES {price} to Paybill {paybill}, account {number}."),
    EXPIRING_1D_AUTO(false, "Hi {name}, your {package} internet renews automatically tomorrow from your KES {balance} credit. Account {number}."),
    DUE_TODAY(false, "Hi {name}, your {package} internet of KES {price} is due today. Pay to Paybill {paybill}, account {number}."),
    DUE_TODAY_AUTO(false, "Hi {name}, your {package} internet renews automatically today from your KES {balance} credit. Account {number}."),
    GRACE_STARTED(false, "Hi {name}, your internet has expired. {grace_days} days left before it is restricted. Pay KES {price} to Paybill {paybill}, acc {number}."),
    RESTRICTED(false, "Hi {name}, your internet is restricted. Pay KES {price} to Paybill {paybill}, account {number} to restore it instantly."),
    PAYMENT_RECEIVED_RENEWED(true, "KES {amount} received ({receipt}). {package} renewed until {expires_on}. Thank you, {name}."),
    PAYMENT_RECEIVED_RESTORED(true, "KES {amount} received ({receipt}). {package} renewed until {expires_on}. Internet is back within a minute; if not, restart your router."),
    PAYMENT_RECEIVED_ACTIVATED(true, "KES {amount} received ({receipt}). {package} is active until {expires_on}. Welcome, {name}!"),
    PAYMENT_RECEIVED_MULTI(true, "KES {amount} received ({receipt}). Renewed: {lines}. Thank you, {name}."),
    PAYMENT_RECEIVED_PARTIAL(true, "KES {amount} received ({receipt}). Pay KES {due} more to Paybill {paybill}, account {number} to renew {package}."),
    PAYMENT_RECEIVED_CREDIT(true, "KES {amount} received ({receipt}). Your credit is now KES {balance}, account {number}. Thank you, {name}."),
    PAYMENT_RECEIVED_POSTPAID(true, "KES {amount} received ({receipt}). Balance due: KES {due}, account {number}. Thank you, {name}."),
    PAYMENT_UNMATCHED(true, "We received KES {amount} with account {reference}, which we did not recognise. Next time use your customer number, which starts with {code}."),
    PACKAGE_CHANGED_NOW(true, "Hi {name}, your internet is now {package}. Enjoy the new speed."),
    PACKAGE_CHANGED_NEXT(true, "Hi {name}, your internet changes to {package} at your next renewal on {expires_on}."),
    /** The line passed its fair-use allowance and is throttled until it renews (docs/09 step 8b, Q15 default). */
    FAIR_USE_REACHED(true, "Hi {name}, you have used your {allowance} fair-use data on {package}, so speed is reduced. Pay KES {price} to Paybill {paybill}, acc {number} to renew now."),
    /** The line used its data cap and is restricted until it renews (Q14 default). */
    DATA_CAP_REACHED(true, "Hi {name}, you have used all {allowance} of data on {package}. Pay KES {price} to Paybill {paybill}, account {number} to renew and reconnect.");

    /** Every placeholder a template may use; none can carry a secret (M3). */
    public static final Set<String> PLACEHOLDERS = Set.of(
            "name", "number", "package", "price", "amount", "receipt", "expires_on", "balance", "due", "paybill",
            "lines", "reference", "code", "grace_days", "allowance");

    /** Long but realistic values: the defaults must fit one part with these (a test checks). */
    public static final Map<String, String> SAMPLE = Map.ofEntries(
            Map.entry("name", "Wanjiku"),
            Map.entry("number", "CB001238"),
            Map.entry("package", "Home Plus 10"),
            Map.entry("price", "2,500"),
            Map.entry("amount", "2,500"),
            Map.entry("receipt", "CB-R000123"),
            Map.entry("expires_on", "17 Nov"),
            Map.entry("balance", "1,500"),
            Map.entry("due", "1,000"),
            Map.entry("paybill", "123456"),
            Map.entry("lines", "CB001238 to 17 Nov, CB001238-2 to 20 Nov"),
            Map.entry("reference", "HOUSE 4"),
            Map.entry("code", "CB"),
            Map.entry("grace_days", "2"),
            Map.entry("allowance", "100 GB"));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private final boolean immediate;
    private final String defaultBody;

    Template(final boolean immediate, final String defaultBody) {
        this.immediate = immediate;
        this.defaultBody = defaultBody;
    }

    public boolean immediate() {
        return immediate;
    }

    public String defaultBody() {
        return defaultBody;
    }

    /** Placeholders in {@code body} that are not allowed (M3); empty means the body is fine. */
    public static Set<String> unknownPlaceholders(final String body) {
        final Set<String> unknown = new LinkedHashSet<>();
        final Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            if (!PLACEHOLDERS.contains(matcher.group(1))) {
                unknown.add(matcher.group(1));
            }
        }
        return unknown;
    }

    /** Fills placeholders; a value that is missing renders as an empty string. */
    public static String render(final String body, final Map<String, String> values) {
        final Matcher matcher = PLACEHOLDER.matcher(body);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString().strip();
    }
}
