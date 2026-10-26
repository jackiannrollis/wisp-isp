package com.corebalt.isp.payments.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.payments.api.PaymentReceived;
import com.corebalt.isp.payments.api.PaymentUnmatched;
import com.corebalt.isp.payments.domain.AccountReference;
import com.corebalt.isp.payments.domain.IncomingPayment;
import com.corebalt.isp.payments.domain.Msisdn;
import com.corebalt.isp.payments.domain.Payment;
import com.corebalt.isp.payments.infrastructure.PaymentRepository;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.outbox.OutboxPublisher;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.tenancy.api.TenantProfile;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns money M-Pesa reports into payments (docs/05 Callback handling):
 *
 * <ol>
 *   <li>P1: a TransID already recorded is a duplicate; nothing happens.
 *   <li>P3, in order: the account reference is exactly a customer number; it is exactly a customer's
 *       old-system reference (ADR 0014); the normalised reference is a customer number; a plain or
 *       hashed payer number belongs to exactly one customer. A masked number is never matched.
 *       Otherwise UNMATCHED, into the suspense queue.
 *   <li>P4: the payment and its event (PaymentReceived, or PaymentUnmatched) commit together.
 * </ol>
 *
 * The account reference and STK Push are the only reliable automatic paths, which is why the pay
 * page and every reminder SMS carry the customer number.
 */
@Service
public class MpesaIngestion {

    public enum MatchedBy {
        STK_REQUEST,
        EXACT_REFERENCE,
        LEGACY_REFERENCE,
        NORMALISED_REFERENCE,
        PHONE,
        PHONE_HASH,
        NONE
    }

    public record Result(UUID paymentId, boolean duplicate, MatchedBy matchedBy) {}

    private final PaymentRepository payments;
    private final CustomerFacade customers;
    private final TenantProfile tenantProfile;
    private final OutboxPublisher outbox;
    private final Clock clock;

    MpesaIngestion(
            final PaymentRepository payments,
            final CustomerFacade customers,
            final TenantProfile tenantProfile,
            final OutboxPublisher outbox,
            final Clock clock) {
        this.payments = payments;
        this.customers = customers;
        this.tenantProfile = tenantProfile;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result ingest(final IncomingPayment incoming) {
        final Optional<Payment> existing = payments.findByProviderTxnId(incoming.transactionId());
        if (existing.isPresent()) {
            return new Result(existing.get().getId(), true, MatchedBy.NONE);
        }
        final Match match = incoming.knownCustomerId() != null
                ? new Match(incoming.knownCustomerId(), MatchedBy.STK_REQUEST)
                : match(incoming.accountRef(), incoming.msisdn());
        final Instant now = clock.instant();
        final Payment payment = Payment.fromProvider(
                UuidV7.next(clock), TenantContext.requireTenantId(), incoming.channel(), incoming.transactionId(),
                incoming.accountRef(), incoming.msisdn(), incoming.payerName(), incoming.amountMinor(),
                incoming.receivedAt(), match.customerId(), incoming.rawJson(), now);
        payments.saveAndFlush(payment);
        if (match.customerId() == null) {
            outbox.publish(new PaymentUnmatched(UuidV7.next(clock), payment.getTenantId(), now, payment.getId()));
        } else {
            published(payment, now);
        }
        return new Result(payment.getId(), false, match.matchedBy());
    }

    /** P4 for a payment that is MATCHED now (on arrival or by staff assignment). */
    void published(final Payment payment, final Instant now) {
        outbox.publish(new PaymentReceived(
                UuidV7.next(clock), payment.getTenantId(), now, payment.getId(), payment.getCustomerId(),
                payment.getAmountMinor(), payment.getCurrency(), payment.getChannel(), payment.getProviderTxnId(),
                payment.getReceivedAt()));
    }

    Match match(final String accountRef, final String msisdn) {
        if (accountRef != null) {
            final Optional<CustomerSummary> exact = customers.findByNumber(accountRef.strip());
            if (exact.isPresent()) {
                return new Match(exact.get().id(), MatchedBy.EXACT_REFERENCE);
            }
            // Before normalisation (ADR 0014): a digits-only old reference such as "1238" would otherwise
            // be read as the short form of a customer number that exists after the import.
            final Optional<CustomerSummary> legacy = customers.findByLegacyAccountRef(accountRef);
            if (legacy.isPresent()) {
                return new Match(legacy.get().id(), MatchedBy.LEGACY_REFERENCE);
            }
            final Optional<CustomerSummary> normalised = AccountReference
                    .normalise(accountRef, tenantProfile.currentTenantCode())
                    .flatMap(customers::findByNumber);
            if (normalised.isPresent()) {
                return new Match(normalised.get().id(), MatchedBy.NORMALISED_REFERENCE);
            }
        }
        final List<CustomerSummary> byPhone = switch (Msisdn.parse(msisdn)) {
            case Msisdn.Plain plain -> customers.findByPhone(plain.e164());
            case Msisdn.Hashed hashed -> customers.findByPhoneHash(hashed.sha256Hex());
            case Msisdn.Masked masked -> List.of();
            case Msisdn.Absent absent -> List.of();
        };
        if (byPhone.size() == 1) {
            return new Match(byPhone.getFirst().id(),
                    Msisdn.parse(msisdn) instanceof Msisdn.Hashed ? MatchedBy.PHONE_HASH : MatchedBy.PHONE);
        }
        return new Match(null, MatchedBy.NONE);
    }

    record Match(UUID customerId, MatchedBy matchedBy) {}
}
