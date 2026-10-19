package com.corebalt.isp.payments.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.payments.api.ManualPaymentRequest;
import com.corebalt.isp.payments.api.MatchedPayments;
import com.corebalt.isp.payments.api.PaymentDto;
import com.corebalt.isp.payments.api.PaymentReceived;
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
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records payments and announces them (docs/05). The payment row, its raw payload (P2) and the
 * {@link PaymentReceived} outbox row commit together (P4); billing credits the wallet from the
 * event. Manual payments need {@code payments.record_manual}, a reference and an audit row (P5).
 */
@Service
public class PaymentService implements MatchedPayments {

    private final PaymentRepository payments;
    private final CustomerFacade customers;
    private final OutboxPublisher outbox;
    private final AuditTrail audit;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    PaymentService(
            final PaymentRepository payments,
            final CustomerFacade customers,
            final OutboxPublisher outbox,
            final AuditTrail audit,
            final JsonMapper jsonMapper,
            final Clock clock) {
        this.payments = payments;
        this.customers = customers;
        this.outbox = outbox;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
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
        return PageResponse.of(
                customerId == null ? payments.findAll(page) : payments.findByCustomerId(customerId, page),
                PaymentService::toDto);
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
