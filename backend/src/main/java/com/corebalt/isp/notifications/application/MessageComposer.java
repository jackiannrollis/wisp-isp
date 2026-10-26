package com.corebalt.isp.notifications.application;

import com.corebalt.isp.billing.api.Receipts;
import com.corebalt.isp.billing.api.Wallets;
import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.network.api.UsageThresholdReached;
import com.corebalt.isp.notifications.domain.Template;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.payments.api.PaymentInstructions;
import com.corebalt.isp.payments.api.PaymentUnmatched;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.subscriptions.api.CreditApplied;
import com.corebalt.isp.subscriptions.api.CustomerImports;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory;
import com.corebalt.isp.subscriptions.api.SubscriptionDirectory.LineInfo;
import com.corebalt.isp.subscriptions.api.SubscriptionPackageChanged;
import com.corebalt.isp.subscriptions.api.SubscriptionReminderDue;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides which message an event deserves and fills it (docs/04 Reminders, docs/05). The payment
 * SMS is one message per payment, chosen from what the credit did ({@link CreditApplied}); its
 * dedupe key is the payment ID (M1). Values never include secrets (M3).
 */
@Service
public class MessageComposer {

    private static final Logger log = LoggerFactory.getLogger(MessageComposer.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final NotificationQueue queue;
    private final CustomerFacade customers;
    private final SubscriptionDirectory lines;
    private final PackageCatalog packages;
    private final Receipts receipts;
    private final Wallets wallets;
    private final PaymentInstructions payments;
    private final TenantProfile tenantProfile;
    private final CustomerImports imports;

    MessageComposer(
            final NotificationQueue queue,
            final CustomerFacade customers,
            final SubscriptionDirectory lines,
            final PackageCatalog packages,
            final Receipts receipts,
            final Wallets wallets,
            final PaymentInstructions payments,
            final TenantProfile tenantProfile,
            final CustomerImports imports) {
        this.queue = queue;
        this.customers = customers;
        this.lines = lines;
        this.packages = packages;
        this.receipts = receipts;
        this.wallets = wallets;
        this.payments = payments;
        this.tenantProfile = tenantProfile;
        this.imports = imports;
    }

    /** The "we've moved" notice went to an import batch (ADR 0014): how many customers it has and how many messages were queued now. */
    public record MovedNotices(UUID importBatchId, int customers, int queued) {}

    /**
     * One "we've moved" SMS per customer of an import batch, with their new number and, when kept,
     * their old reference; once per batch and customer (M1), in the sending window (M2). Customers
     * without a phone, or already notified for this batch, are skipped.
     */
    @Transactional
    public MovedNotices movedNotices(final UUID batchId) {
        imports.batch(batchId).orElseThrow(() -> new NotFoundException("Import batch"));
        final List<UUID> customerIds = imports.customersOf(batchId);
        int queued = 0;
        for (final UUID customerId : customerIds) {
            final Map<String, String> values = customerValues(customerId);
            final Optional<String> legacy = customers.legacyAccountRefOf(customerId);
            legacy.ifPresent(reference -> values.put("legacy_ref", reference));
            final Optional<String> phone = customers.phoneOf(customerId);
            if (phone.isPresent() && queue.enqueue(customerId, phone.get(),
                    legacy.isPresent() ? Template.MOVED_NOTICE_LEGACY : Template.MOVED_NOTICE, values,
                    "MOVED_NOTICE:" + batchId + ":" + customerId)) {
                queued++;
            }
        }
        return new MovedNotices(batchId, customerIds.size(), queued);
    }

    @Transactional
    public void reminder(final SubscriptionReminderDue event) {
        final Optional<LineInfo> line = lines.line(event.subscriptionId());
        if (line.isEmpty()) {
            return;
        }
        final Template template = switch (event.eventKey()) {
            case "EXPIRING_3D" -> event.renewsAutomatically() ? Template.EXPIRING_3D_AUTO : Template.EXPIRING_3D;
            case "EXPIRING_1D" -> event.renewsAutomatically() ? Template.EXPIRING_1D_AUTO : Template.EXPIRING_1D;
            case "DUE_TODAY" -> event.renewsAutomatically() ? Template.DUE_TODAY_AUTO : Template.DUE_TODAY;
            case "GRACE_STARTED" -> Template.GRACE_STARTED;
            case "RESTRICTED" -> Template.RESTRICTED;
            default -> null;
        };
        if (template == null) {
            log.warn("No template for reminder {}", event.eventKey());
            return;
        }
        final Map<String, String> values = customerValues(event.customerId());
        packageValues(line.get().nextPackageId(), values);
        values.put("expires_on", day(event.expiresAt()));
        values.put("grace_days", String.valueOf(line.get().graceDays()));
        values.put("balance", money(wallets.balance(event.customerId(), Money.KES)));
        send(event.customerId(), template, values, event.dedupeKey());
    }

    @Transactional
    public void creditApplied(final CreditApplied event) {
        if (!"PAYMENT".equals(event.entryType())) {
            return; // staff adjustments are not payments; no "received" message
        }
        final Map<String, String> values = customerValues(event.customerId());
        final Currency currency = new Currency(event.currency());
        values.put("amount", money(currency.of(event.amountMinor())));
        values.put("receipt", receipts.numberForPayment(event.referenceId()).orElse(""));
        values.put("balance", money(currency.of(Math.max(0, event.balanceAfterMinor()))));
        final Template template;
        if (event.renewed().size() > 1) {
            template = Template.PAYMENT_RECEIVED_MULTI;
            values.put("lines", event.renewed().stream()
                    .map(r -> lines.line(r.subscriptionId()).map(LineInfo::pppoeUsername).orElse("") + " to " + day(r.expiresAt()))
                    .collect(Collectors.joining(", ")));
        } else if (event.renewed().size() == 1) {
            final CreditApplied.RenewedLine renewed = event.renewed().getFirst();
            template = renewed.restored() ? Template.PAYMENT_RECEIVED_RESTORED
                    : "REJECT".equals(renewed.previousProfile()) ? Template.PAYMENT_RECEIVED_ACTIVATED
                    : Template.PAYMENT_RECEIVED_RENEWED;
            packageValues(renewed.packageId(), values);
            values.put("expires_on", day(renewed.expiresAt()));
        } else if (event.nextDue() != null) {
            template = Template.PAYMENT_RECEIVED_PARTIAL;
            packageValues(event.nextDue().packageId(), values);
            values.put("due", money(currency.of(event.nextDue().amountDueMinor())));
        } else if (event.balanceAfterMinor() < 0) {
            template = Template.PAYMENT_RECEIVED_POSTPAID;
            values.put("due", money(currency.of(-event.balanceAfterMinor())));
        } else {
            template = Template.PAYMENT_RECEIVED_CREDIT;
        }
        send(event.customerId(), template, values, "PAYMENT_RECEIVED:" + event.referenceId());
    }

    /** "Reference not recognised", only when Daraja sent the payer's number unmasked. */
    @Transactional
    public void unmatched(final PaymentUnmatched event) {
        payments.unmatched(event.paymentId()).ifPresent(payment -> payment.plainPhone().ifPresent(phone -> {
            final Map<String, String> values = new HashMap<>();
            values.put("amount", money(Money.ofMinor(payment.amountMinor(), "KES")));
            values.put("reference", Optional.ofNullable(payment.accountRef()).filter(r -> !r.isBlank()).orElse("(none)"));
            values.put("code", tenantProfile.currentTenantCode());
            queue.enqueue(null, phone, Template.PAYMENT_UNMATCHED, values, "PAYMENT_UNMATCHED:" + event.paymentId());
        }));
    }

    /**
     * One SMS per line, cycle and kind (M1, Q15 default): the line passed its fair-use allowance or data
     * cap. The price is what the next renewal sells; paying it renews at once and starts a new cycle.
     */
    @Transactional
    public void usageThresholdReached(final UsageThresholdReached event) {
        final Map<String, String> values = customerValues(event.customerId());
        lines.line(event.subscriptionId()).ifPresent(l -> packageValues(l.nextPackageId(), values));
        values.put("allowance", gigabytes(event.limitBytes()));
        final boolean fairUse = event.kind() == UsageThresholdReached.Kind.FAIR_USE;
        send(event.customerId(), fairUse ? Template.FAIR_USE_REACHED : Template.DATA_CAP_REACHED, values,
                "USAGE:" + event.kind() + ":" + event.subscriptionId() + ":" + event.cycleStartedAt());
    }

    @Transactional
    public void packageChanged(final SubscriptionPackageChanged event) {
        final Map<String, String> values = customerValues(event.customerId());
        packageValues(event.toPackageId(), values);
        final boolean now = "NOW".equals(event.timing());
        if (!now) {
            lines.line(event.subscriptionId()).ifPresent(l -> values.put("expires_on", day(l.expiresAt())));
        }
        send(event.customerId(), now ? Template.PACKAGE_CHANGED_NOW : Template.PACKAGE_CHANGED_NEXT, values,
                "PACKAGE_CHANGED:" + event.eventId());
    }

    private void send(final UUID customerId, final Template template, final Map<String, String> values, final String dedupeKey) {
        final Optional<String> phone = customers.phoneOf(customerId);
        if (phone.isEmpty()) {
            return;
        }
        queue.enqueue(customerId, phone.get(), template, values, dedupeKey);
    }

    private Map<String, String> customerValues(final UUID customerId) {
        final Map<String, String> values = new HashMap<>();
        customers.find(customerId).ifPresent((CustomerSummary c) -> {
            values.put("name", c.name().strip().split("\\s+")[0]);
            values.put("number", c.number());
        });
        payments.shortcode().ifPresent(code -> values.put("paybill", code));
        values.put("code", tenantProfile.currentTenantCode());
        return values;
    }

    private void packageValues(final UUID packageId, final Map<String, String> values) {
        if (packageId == null) {
            return;
        }
        packages.terms(packageId).ifPresent((PackageTerms terms) -> {
            values.put("package", terms.name());
            values.put("price", money(terms.price()));
        });
    }

    /** Allowances are sold in decimal gigabytes (10^9 bytes): {@code 100 GB}, {@code 1.5 GB}. */
    static String gigabytes(final long bytes) {
        final BigDecimal gb = BigDecimal.valueOf(bytes).movePointLeft(9).stripTrailingZeros();
        return (gb.scale() > 1 ? gb.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros() : gb).toPlainString()
                + " GB";
    }

    private String day(final Instant instant) {
        if (instant == null) {
            return "";
        }
        final ZoneId zone = tenantProfile.currentPolicy().zone();
        return DAY.format(instant.atZone(zone));
    }

    /** {@code 2,500} for whole shillings, {@code 2,500.50} otherwise. */
    static String money(final Money money) {
        final BigDecimal major = money.toMajor();
        final DecimalFormat format = new DecimalFormat(major.stripTrailingZeros().scale() <= 0 ? "#,##0" : "#,##0.00");
        return format.format(major);
    }

    private record Currency(String code) {
        Money of(final long minor) {
            return Money.ofMinor(minor, code);
        }
    }
}
