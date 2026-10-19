package com.corebalt.isp.billing.application;

import com.corebalt.isp.billing.api.ReceiptIssued;
import com.corebalt.isp.billing.api.Receipts;
import com.corebalt.isp.billing.api.WalletCredited;
import com.corebalt.isp.billing.api.WalletDebited;
import com.corebalt.isp.billing.api.WalletDtos.LedgerEntryDto;
import com.corebalt.isp.billing.api.WalletDtos.ReceiptDto;
import com.corebalt.isp.billing.api.WalletDtos.WalletDto;
import com.corebalt.isp.billing.api.Wallets;
import com.corebalt.isp.billing.infrastructure.Ledger;
import com.corebalt.isp.billing.infrastructure.Ledger.Posted;
import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.payments.api.PaymentReceived;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.ConflictException;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallets and their ledger (docs/05, ADR 0005). Billing is the only writer of PAYMENT entries and
 * the only issuer of receipts (P4); renewals and package changes charge through {@link Wallets} in
 * their own transactions.
 */
@Service
public class WalletService implements Wallets, Receipts {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final Ledger ledger;
    private final CustomerFacade customers;
    private final TenantProfile tenantProfile;
    private final OutboxPublisher outbox;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    WalletService(
            final Ledger ledger,
            final CustomerFacade customers,
            final TenantProfile tenantProfile,
            final OutboxPublisher outbox,
            final AuditTrail audit,
            final JdbcClient jdbc,
            final Clock clock) {
        this.ledger = ledger;
        this.customers = customers;
        this.tenantProfile = tenantProfile;
        this.outbox = outbox;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Money balance(final UUID customerId, final Currency currency) {
        return Money.of(ledger.balance(customerId), currency);
    }

    @Override
    @Transactional
    public ChargeResult charge(
            final UUID customerId, final Money amount, final ChargeType type, final UUID referenceId, final String description) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("B3: a charge is a positive amount, posted negative");
        }
        final Ledger.Result result = ledger.post(new Ledger.Posting(
                customerId, amount.currency().getCurrencyCode(), type.name(), -amount.minorUnits(),
                type.name(), referenceId, description, null, CurrentActor.staffId().orElse(null), false));
        return switch (result) {
            case Ledger.Duplicate d -> ChargeResult.ALREADY_CHARGED;
            case Ledger.Insufficient i -> ChargeResult.INSUFFICIENT_BALANCE;
            case Posted posted -> {
                outbox.publish(new WalletDebited(
                        UuidV7.next(clock), TenantContext.requireTenantId(), posted.at(), customerId, posted.walletId(),
                        posted.entryId(), type.name(), amount.minorUnits(), amount.currency().getCurrencyCode(),
                        type.name(), referenceId));
                yield ChargeResult.CHARGED;
            }
        };
    }

    /**
     * Credits a received payment (from {@link PaymentReceived}), issues its receipt (B7) and
     * announces both. A second delivery of the same payment writes nothing: one PAYMENT entry per
     * payment, enforced by the ledger's unique reference.
     */
    @Transactional
    public void creditPayment(final PaymentReceived payment) {
        final Ledger.Result result = ledger.post(new Ledger.Posting(
                payment.customerId(), payment.currency(), "PAYMENT", payment.amountMinor(), "PAYMENT",
                payment.paymentId(), payment.channel() + " " + payment.reference(), null, null, false));
        if (!(result instanceof Posted posted)) {
            log.info("Payment {} already credited", payment.paymentId());
            return;
        }
        final UUID receiptId = UuidV7.next(clock);
        final String number = nextReceiptNumber();
        jdbc.sql(
                        """
                        insert into billing_receipt
                            (id, tenant_id, number, payment_id, customer_id, ledger_entry_id, amount_minor, currency, issued_at)
                        values (:id, :tenantId, :number, :paymentId, :customerId, :entryId, :amount, :currency, :at)
                        """)
                .param("id", receiptId)
                .param("tenantId", TenantContext.requireTenantId())
                .param("number", number)
                .param("paymentId", payment.paymentId())
                .param("customerId", payment.customerId())
                .param("entryId", posted.entryId())
                .param("amount", payment.amountMinor())
                .param("currency", payment.currency())
                .param("at", Timestamp.from(posted.at()))
                .update();
        outbox.publish(new WalletCredited(
                UuidV7.next(clock), payment.tenantId(), posted.at(), payment.customerId(), posted.walletId(),
                posted.entryId(), "PAYMENT", payment.paymentId(), payment.amountMinor(), payment.currency()));
        outbox.publish(new ReceiptIssued(
                UuidV7.next(clock), payment.tenantId(), posted.at(), receiptId, number, payment.paymentId(),
                payment.customerId(), payment.amountMinor(), payment.currency()));
    }

    /**
     * B1: a correction or grant with a reason and an actor, audited (A1). A positive adjustment is
     * announced like a payment so the customer's lines renew from it through the usual path.
     */
    @Transactional
    public LedgerEntryDto adjust(final UUID customerId, final Money amount, final String reason) {
        customers.find(customerId).orElseThrow(() -> new NotFoundException("Customer"));
        if (amount.isZero()) {
            throw InvalidRequestException.of("amount", "must not be zero");
        }
        final UUID adjustmentId = UuidV7.next(clock);
        final Ledger.Result result = ledger.post(new Ledger.Posting(
                customerId, amount.currency().getCurrencyCode(), "ADJUSTMENT", amount.minorUnits(), "ADJUSTMENT",
                adjustmentId, "Adjustment", reason, CurrentActor.staffId().orElse(null), false));
        if (result instanceof Ledger.Insufficient insufficient) {
            throw new ConflictException("B5: the wallet holds " + insufficient.balanceMinor()
                    + " minor units; an adjustment cannot take it below zero.");
        }
        final Posted posted = (Posted) result;
        audit.record(new AuditEntry(
                "billing.wallet_adjusted", "customer", customerId, null,
                Map.of("amountMinor", amount.minorUnits(), "balanceAfterMinor", posted.balanceAfterMinor()), reason));
        if (amount.isPositive()) {
            outbox.publish(new WalletCredited(
                    UuidV7.next(clock), TenantContext.requireTenantId(), posted.at(), customerId, posted.walletId(),
                    posted.entryId(), "ADJUSTMENT", adjustmentId, amount.minorUnits(), amount.currency().getCurrencyCode()));
        } else {
            outbox.publish(new WalletDebited(
                    UuidV7.next(clock), TenantContext.requireTenantId(), posted.at(), customerId, posted.walletId(),
                    posted.entryId(), "ADJUSTMENT", -amount.minorUnits(), amount.currency().getCurrencyCode(),
                    "ADJUSTMENT", adjustmentId));
        }
        return entry(posted.entryId());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> numberForPayment(final UUID paymentId) {
        return jdbc.sql("select number from billing_receipt where payment_id = :paymentId")
                .param("paymentId", paymentId)
                .query(String.class)
                .optional();
    }

    @Transactional(readOnly = true)
    public WalletDto wallet(final UUID customerId, final Currency currency) {
        customers.find(customerId).orElseThrow(() -> new NotFoundException("Customer"));
        return new WalletDto(customerId, MoneyDto.of(balance(customerId, currency)));
    }

    /** The statement: ledger entries newest first (docs/05 Receipts and invoices). */
    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryDto> entries(final UUID customerId, final Pageable page) {
        final List<LedgerEntryDto> items =
                jdbc.sql(ENTRY_SELECT + """
                                 join billing_wallet w on w.id = e.wallet_id
                                 where w.customer_id = :customerId
                                 order by e.created_at desc, e.id desc
                                 limit :limit offset :offset
                                 """)
                        .param("customerId", customerId)
                        .param("limit", page.getPageSize())
                        .param("offset", page.getOffset())
                        .query(WalletService::entryRow)
                        .list();
        final long total =
                jdbc.sql("select count(*) from billing_ledger_entry e join billing_wallet w on w.id = e.wallet_id where w.customer_id = :customerId")
                        .param("customerId", customerId)
                        .query(Long.class)
                        .single();
        return PageResponse.of(new PageImpl<>(items, page, total), entry -> entry);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReceiptDto> receipts(final UUID customerId, final Pageable page) {
        final List<ReceiptDto> items =
                jdbc.sql("""
                                select id, number, payment_id, customer_id, amount_minor, currency, issued_at
                                from billing_receipt
                                where (cast(:customerId as uuid) is null or customer_id = cast(:customerId as uuid))
                                order by issued_at desc, id desc
                                limit :limit offset :offset
                                """)
                        .param("customerId", customerId)
                        .param("limit", page.getPageSize())
                        .param("offset", page.getOffset())
                        .query((rs, n) -> new ReceiptDto(
                                rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                                rs.getObject(4, UUID.class),
                                MoneyDto.of(Money.ofMinor(rs.getLong(5), rs.getString(6))),
                                rs.getTimestamp(7).toInstant()))
                        .list();
        final long total =
                jdbc.sql("select count(*) from billing_receipt where (cast(:customerId as uuid) is null or customer_id = cast(:customerId as uuid))")
                        .param("customerId", customerId)
                        .query(Long.class)
                        .single();
        return PageResponse.of(new PageImpl<>(items, page, total), receipt -> receipt);
    }

    private LedgerEntryDto entry(final UUID entryId) {
        return jdbc.sql(ENTRY_SELECT + " where e.id = :id").param("id", entryId).query(WalletService::entryRow).single();
    }

    /** B7: {@code <tenant code>-R<6 digits>}, from a per-tenant counter locked until commit; never reused. */
    private String nextReceiptNumber() {
        final long sequence =
                jdbc.sql("""
                                insert into billing_receipt_counter as c (tenant_id, last_value)
                                values (:tenantId, 1)
                                on conflict (tenant_id) do update set last_value = c.last_value + 1
                                returning c.last_value
                                """)
                        .param("tenantId", TenantContext.requireTenantId())
                        .query(Long.class)
                        .single();
        return tenantProfile.currentTenantCode() + "-R" + String.format("%06d", sequence);
    }

    private static final String ENTRY_SELECT = """
            select e.id, e.type, e.amount_minor, e.balance_after_minor, e.currency, e.reference_type,
                   e.reference_id, e.description, e.reason, e.created_at
            from billing_ledger_entry e
            """;

    private static LedgerEntryDto entryRow(final java.sql.ResultSet rs, final int row) throws java.sql.SQLException {
        final String currency = rs.getString(5);
        return new LedgerEntryDto(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                MoneyDto.of(Money.ofMinor(rs.getLong(3), currency)),
                MoneyDto.of(Money.ofMinor(rs.getLong(4), currency)),
                rs.getString(6),
                rs.getObject(7, UUID.class),
                rs.getString(8),
                rs.getString(9),
                rs.getTimestamp(10).toInstant());
    }
}
