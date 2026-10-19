package com.corebalt.isp.payments.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.payments.api.ManualPaymentRequest;
import com.corebalt.isp.payments.api.MatchedPayments;
import com.corebalt.isp.payments.api.PaymentDto;
import com.corebalt.isp.payments.api.PaymentInstructions;
import com.corebalt.isp.payments.api.PaymentReceived;
import com.corebalt.isp.payments.domain.Msisdn;
import com.corebalt.isp.payments.domain.Payment;
import com.corebalt.isp.payments.infrastructure.PaymentRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records payments and announces them (docs/05). The payment row, its raw payload (P2) and the
 * {@link PaymentReceived} outbox row commit together (P4); billing credits the wallet from the
 * event. Manual payments need {@code payments.record_manual}, a reference and an audit row (P5).
 */
@Service
public class PaymentService implements MatchedPayments, PaymentInstructions {

    private final PaymentRepository payments;
    private final CustomerFacade customers;
    private final OutboxPublisher outbox;
    private final AuditTrail audit;
    private final JsonMapper jsonMapper;
    private final MpesaIngestion ingestion;
    private final java.util.function.Supplier<Optional<String>> mpesaShortcode;
    private final Clock clock;

    PaymentService(
            final PaymentRepository payments,
            final CustomerFacade customers,
            final OutboxPublisher outbox,
            final AuditTrail audit,
            final JsonMapper jsonMapper,
            final MpesaIngestion ingestion,
            final org.springframework.jdbc.core.simple.JdbcClient jdbc,
            final Clock clock) {
        this.payments = payments;
        this.customers = customers;
        this.outbox = outbox;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
        this.ingestion = ingestion;
        this.mpesaShortcode = () -> jdbc.sql("select shortcode from payments_mpesa_config").query(String.class).optional();
        this.clock = clock;
    }

    @Transactional
    public PaymentDto recordManual(final ManualPaymentRequest request) {
        final Money amount = request.amount().toMoney();
        if (!amount.isPositive()) {
            throw InvalidRequestException.of("amount", "must be positive");
        }
        customers.find(request.customerId()).orElseThrow(() -> new NotFoundException("Customer"));
        final String reference = request.reference().strip();
        if (payments.existsByProviderTxnId(reference)) {
            throw new ConflictException("P1: a payment with reference " + reference + " is already recorded.");
        }
        final Instant now = clock.instant();
        final Payment payment =
                Payment.recordedManually(
                        UuidV7.next(clock),
                        TenantContext.requireTenantId(),
                        request.channel(),
                        reference,
                        request.payerName(),
                        amount.minorUnits(),
                        amount.currency().getCurrencyCode(),
                        request.receivedAt() == null ? now : request.receivedAt(),
                        request.customerId(),
                        CurrentActor.staffId().orElse(null),
                        jsonMapper.writeValueAsString(request),
                        now);
        payments.saveAndFlush(payment);

        final Map<String, Object> after = new LinkedHashMap<>();
        after.put("channel", payment.getChannel());
        after.put("reference", reference);
        after.put("amountMinor", payment.getAmountMinor());
        after.put("customerId", payment.getCustomerId().toString());
        audit.record(new AuditEntry("payments.manual_recorded", "payment", payment.getId(), null, after, request.note()));
        outbox.publish(new PaymentReceived(
                UuidV7.next(clock),
                payment.getTenantId(),
                now,
                payment.getId(),
                payment.getCustomerId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getChannel(),
                reference,
                payment.getReceivedAt()));
        return toDto(payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentDto> list(final UUID customerId, final Pageable page) {
        return list(new PaymentFilter(customerId, null, null, null, null), page);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentDto> list(final PaymentFilter filter, final Pageable page) {
        Specification<Payment> where = Specification.unrestricted();
        if (filter.customerId() != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("customerId"), filter.customerId()));
        }
        if (filter.status() != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.channel() != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("channel"), filter.channel()));
        }
        if (filter.receivedFrom() != null) {
            where = where.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("receivedAt"), filter.receivedFrom()));
        }
        if (filter.receivedBefore() != null) {
            where = where.and((root, query, cb) -> cb.lessThan(root.get("receivedAt"), filter.receivedBefore()));
        }
        return PageResponse.of(payments.findAll(where, page), PaymentService::toDto);
    }

    /** Staff list filters; null means any. Received times are instants, [from, before). */
    public record PaymentFilter(UUID customerId, String status, String channel, Instant receivedFrom, Instant receivedBefore) {}

    /**
     * The suspense queue (P3): unmatched payments, newest first, with what staff need to assign
     * them. The payer's number is shown masked (A3). For a masked number, the shortlist holds the
     * customers whose phone fits its visible digits; it is a hint for staff, never an automatic match.
     */
    @Transactional(readOnly = true)
    public PageResponse<SuspenseItem> suspense(final Pageable page) {
        return PageResponse.of(payments.findUnmatched(page), p -> new SuspenseItem(
                p.getId(), p.getChannel(), p.getProviderTxnId(), p.getAccountRef(), p.getPayerName(),
                Msisdn.display(p.getMsisdn()),
                MoneyDto.of(Money.ofMinor(p.getAmountMinor(), p.getCurrency())), p.getReceivedAt(),
                Msisdn.parse(p.getMsisdn()) instanceof Msisdn.Masked masked
                        ? customers.findByPhoneDigits(masked.prefix(), masked.suffix())
                        : List.of()));
    }

    /** P3: staff assign an unmatched payment; audited, then credited like any matched payment (P4). */
    @Transactional
    public PaymentDto assign(final UUID paymentId, final UUID customerId) {
        final Payment payment = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        customers.find(customerId).orElseThrow(() -> new NotFoundException("Customer"));
        if (!"UNMATCHED".equals(payment.getStatus())) {
            throw new ConflictException("P3: payment " + payment.getProviderTxnId() + " is already " + payment.getStatus() + ".");
        }
        final Instant now = clock.instant();
        payment.assign(customerId, CurrentActor.staffId().orElse(null), now);
        audit.record(new AuditEntry("payments.assigned", "payment", payment.getId(),
                Map.of("status", "UNMATCHED"), Map.of("status", "MATCHED", "customerId", customerId.toString()), null));
        ingestion.published(payment, now);
        return toDto(payment);
    }

    public record SuspenseItem(
            UUID id, String channel, String reference, String accountRef, String payerName, String payerNumber,
            MoneyDto amount, Instant receivedAt, List<com.corebalt.isp.customers.api.CustomerSummary> likelyPayers) {}

    @Override
    @Transactional(readOnly = true)
    public Optional<String> shortcode() {
        return mpesaShortcode.get();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Unmatched> unmatched(final UUID paymentId) {
        return payments.findById(paymentId)
                .filter(p -> "UNMATCHED".equals(p.getStatus()))
                .map(p -> new Unmatched(p.getAmountMinor(), p.getAccountRef(),
                        Msisdn.parse(p.getMsisdn()) instanceof Msisdn.Plain plain ? Optional.of(plain.e164()) : Optional.empty()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Matched> matchedBetween(final Instant from, final Instant to) {
        return payments.findMatchedBetween(from, to).stream()
                .map(p -> new Matched(p.getId(), p.getCustomerId(), p.getAmountMinor(), p.getMatchedAt()))
                .toList();
    }

    private static PaymentDto toDto(final Payment payment) {
        return new PaymentDto(
                payment.getId(),
                payment.getChannel(),
                payment.getProviderTxnId(),
                MoneyDto.of(Money.ofMinor(payment.getAmountMinor(), payment.getCurrency())),
                payment.getReceivedAt(),
                payment.getStatus(),
                payment.getCustomerId(),
                payment.getMatchedAt());
    }
}
