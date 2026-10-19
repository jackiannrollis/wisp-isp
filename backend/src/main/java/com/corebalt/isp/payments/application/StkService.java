package com.corebalt.isp.payments.application;

import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.payments.domain.IncomingPayment;
import com.corebalt.isp.payments.domain.MpesaCallbacks;
import com.corebalt.isp.payments.infrastructure.daraja.DarajaClient;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * STK Push (docs/05 STK Push, P6). The request row is committed before Daraja is called, and Daraja
 * is never called inside a transaction. State changes happen under the row lock:
 *
 * <pre>
 * PENDING  -> COMPLETED   result callback with ResultCode 0, or a query saying 0
 * PENDING  -> FAILED      callback or query with any other code (1032 cancelled, 1037, 2001, ...)
 * PENDING  -> TIMED_OUT   still unanswered 5 minutes after the push; never retried
 * TIMED_OUT -> COMPLETED  a late success callback: the money was taken, so it is still credited
 * </pre>
 *
 * The customer is known from the request, so a successful payment is matched without P3. If the
 * C2B confirmation of the same payment arrives first, P1 makes it one payment and this only links.
 */
@Service
public class StkService {

    private static final Logger log = LoggerFactory.getLogger(StkService.class);

    public static final Duration QUERY_AFTER = Duration.ofMinutes(2);
    public static final Duration TIME_OUT_AFTER = Duration.ofMinutes(5);

    public record StkRequestDto(UUID id, UUID customerId, MoneyDto amount, String status, String resultCode,
                                String resultDesc, UUID paymentId, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CustomerFacade customers;
    private final MpesaSettings settings;
    private final DarajaClient daraja;
    private final MpesaIngestion ingestion;
    private final Clock clock;

    StkService(
            final JdbcClient jdbc,
            final TransactionTemplate tx,
            final CustomerFacade customers,
            final MpesaSettings settings,
            final DarajaClient daraja,
            final MpesaIngestion ingestion,
            final Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.customers = customers;
        this.settings = settings;
        this.daraja = daraja;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    /** Starts a push to the customer's phone (or {@code phone}); runs outside any transaction. */
    public StkRequestDto initiate(final UUID customerId, final Money amount, final String phone) {
        if (!amount.isPositive() || amount.minorUnits() % 100 != 0) {
            throw InvalidRequestException.of("amount", "M-Pesa takes whole shillings");
        }
        final CustomerSummary customer =
                tx.execute(s -> customers.find(customerId)).orElseThrow(() -> new NotFoundException("Customer"));
        final String target = Optional.ofNullable(phone).filter(p -> !p.isBlank())
                .or(() -> tx.execute(s -> customers.phoneOf(customerId)))
                .flatMap(customers::normalisePhone)
                .orElseThrow(() -> InvalidRequestException.of("phone", "not a valid phone number"));
        final DarajaClient.Credentials credentials = tx.execute(s -> settings.credentials());
        final String callbackUrl = tx.execute(s -> settings.callbackUrl("stk"));
        final UUID id = UuidV7.next(clock);
        final Instant now = clock.instant();
        tx.executeWithoutResult(s -> jdbc.sql("""
                        insert into payments_stk_request
                            (id, tenant_id, customer_id, amount_minor, phone, account_ref, status, created_at, updated_at)
                        values (:id, :tenantId, :customerId, :amount, :phone, :ref, 'PENDING', :now, :now)
                        """)
                .param("id", id)
                .param("tenantId", TenantContext.requireTenantId())
                .param("customerId", customerId)
                .param("amount", amount.minorUnits())
                .param("phone", target)
                .param("ref", customer.number())
                .param("now", Timestamp.from(now))
                .update());
        try {
            final DarajaClient.StkPushResult pushed = daraja.stkPush(
                    credentials, amount.minorUnits() / 100, target.substring(1), customer.number(), "Internet", callbackUrl);
            tx.executeWithoutResult(s -> jdbc.sql("""
                            update payments_stk_request
                            set checkout_request_id = :checkout, merchant_request_id = :merchant, updated_at = :now
                            where id = :id
                            """)
                    .param("checkout", pushed.checkoutRequestId())
                    .param("merchant", pushed.merchantRequestId())
                    .param("now", Timestamp.from(clock.instant()))
                    .param("id", id)
                    .update());
        } catch (DarajaClient.DarajaException e) {
            tx.executeWithoutResult(s -> finish(id, "FAILED", e.errorCode(), "push refused"));
        }
        return get(id);
    }

    /** A result callback, inside the callback's transaction; returns the payment, if any. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID onCallback(final MpesaCallbacks.StkResult result, final String raw) {
        final Optional<Request> request = lock(result.checkoutRequestId());
        if (request.isEmpty()) {
            log.warn("STK result for an unknown checkout request");
            return null;
        }
        final Request stk = request.get();
        if (!result.succeeded()) {
            if ("PENDING".equals(stk.status())) {
                finish(stk.id(), "FAILED", result.resultCode(), result.resultDesc());
            }
            return null;
        }
        if (result.receiptNumber() == null || result.amountMinor() == null) {
            throw new MpesaCallbacks.UnreadableCallback("success without receipt number or amount");
        }
        final UUID paymentId = ingestion.ingest(new IncomingPayment(
                "MPESA_STK", result.receiptNumber(), result.amountMinor(), result.transactionTime(), stk.accountRef(),
                result.phone(), null, raw, stk.customerId())).paymentId();
        if (!"COMPLETED".equals(stk.status()) || stk.paymentId() == null) {
            jdbc.sql("""
                            update payments_stk_request
                            set status = 'COMPLETED', result_code = :code, result_desc = :desc, payment_id = :payment, updated_at = :now
                            where id = :id
                            """)
                    .param("code", result.resultCode())
                    .param("desc", result.resultDesc())
                    .param("payment", paymentId)
                    .param("now", Timestamp.from(clock.instant()))
                    .param("id", stk.id())
                    .update();
        }
        return paymentId;
    }

    /**
     * P6, for the current tenant: query pushes unanswered after 2 minutes; time out those still
     * unanswered after 5. Daraja is called outside any transaction; the answer is applied only if
     * the request is still PENDING, so a callback that arrived meanwhile wins.
     */
    public void followUpPending() {
        final Instant now = clock.instant();
        final List<Request> pending = tx.execute(s -> jdbc.sql("""
                        select id, customer_id, account_ref, status, checkout_request_id, payment_id, created_at
                        from payments_stk_request
                        where status = 'PENDING' and created_at < :cutoff
                        order by created_at limit 100
                        """)
                .param("cutoff", Timestamp.from(now.minus(QUERY_AFTER)))
                .query(StkService::request)
                .list());
        for (final Request stk : pending == null ? List.<Request>of() : pending) {
            if (!stk.createdAt().isAfter(now.minus(TIME_OUT_AFTER))) {
                tx.executeWithoutResult(s -> lockById(stk.id())
                        .filter(r -> "PENDING".equals(r.status()))
                        .ifPresent(r -> finish(r.id(), "TIMED_OUT", null, "no answer after 5 minutes")));
                continue;
            }
            if (stk.checkoutRequestId() == null) {
                continue;
            }
            final DarajaClient.StkQueryResult answer;
            try {
                answer = daraja.stkQuery(tx.execute(s -> settings.credentials()), stk.checkoutRequestId());
            } catch (DarajaClient.DarajaException e) {
                log.info("STK query for {} failed ({}); retried next run", stk.id(), e.errorCode());
                continue;
            }
            tx.executeWithoutResult(s -> lockById(stk.id()).filter(r -> "PENDING".equals(r.status())).ifPresent(r -> {
                if (answer.stillProcessing()) {
                    jdbc.sql("update payments_stk_request set queried_at = :now, updated_at = :now where id = :id")
                            .param("now", Timestamp.from(clock.instant()))
                            .param("id", r.id())
                            .update();
                } else {
                    // ResultCode 0: paid; the payment itself arrives by the result or C2B callback.
                    finish(r.id(), "0".equals(answer.resultCode()) ? "COMPLETED" : "FAILED", answer.resultCode(), answer.resultDesc());
                }
            }));
        }
    }

    @Transactional(readOnly = true)
    public StkRequestDto get(final UUID id) {
        return jdbc.sql("""
                        select id, customer_id, amount_minor, currency, status, result_code, result_desc, payment_id, created_at
                        from payments_stk_request where id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new StkRequestDto(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        MoneyDto.of(Money.ofMinor(rs.getLong(3), rs.getString(4))), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getObject(8, UUID.class), rs.getTimestamp(9).toInstant()))
                .optional()
                .orElseThrow(() -> new NotFoundException("STK request"));
    }

    private void finish(final UUID id, final String status, final String code, final String desc) {
        jdbc.sql("""
                        update payments_stk_request
                        set status = :status, result_code = :code, result_desc = left(:desc, 200), queried_at = :now, updated_at = :now
                        where id = :id
                        """)
                .param("status", status)
                .param("code", code)
                .param("desc", desc)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
    }

    private Optional<Request> lock(final String checkoutRequestId) {
        return jdbc.sql("""
                        select id, customer_id, account_ref, status, checkout_request_id, payment_id, created_at
                        from payments_stk_request where checkout_request_id = :checkout for update
                        """)
                .param("checkout", checkoutRequestId)
                .query(StkService::request)
                .optional();
    }

    private Optional<Request> lockById(final UUID id) {
        return jdbc.sql("""
                        select id, customer_id, account_ref, status, checkout_request_id, payment_id, created_at
                        from payments_stk_request where id = :id for update
                        """)
                .param("id", id)
                .query(StkService::request)
                .optional();
    }

    private static Request request(final java.sql.ResultSet rs, final int row) throws java.sql.SQLException {
        return new Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getTimestamp(7).toInstant());
    }

    private record Request(UUID id, UUID customerId, String accountRef, String status, String checkoutRequestId,
                           UUID paymentId, Instant createdAt) {}
}
