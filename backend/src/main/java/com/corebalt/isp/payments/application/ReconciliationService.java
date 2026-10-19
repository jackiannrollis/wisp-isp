package com.corebalt.isp.payments.application;

import com.corebalt.isp.payments.api.LedgerCreditCheck;
import com.corebalt.isp.payments.domain.Payment;
import com.corebalt.isp.payments.infrastructure.PaymentRepository;
import com.corebalt.isp.payments.infrastructure.daraja.DarajaClient;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.csv.CsvReader;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.tenant.TenantContext;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.InvalidRequestException.Violation;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.PageResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reconciliation (docs/05): an M-Pesa statement exported from the organisation portal is compared
 * with the platform's M-Pesa payments over the statement's period, in both directions, and matched
 * payments without a ledger entry are listed (the P4 guard). Statement formats vary, so columns
 * are found by name: {@code Receipt No.} and {@code Paid In} are required; {@code Completion Time},
 * {@code Transaction Status} and {@code Details} are used when present. Only completed money in
 * counts. A missing receipt can then be investigated with Transaction Status, whose result becomes a
 * payment through the usual callback path.
 */
@Service
public class ReconciliationService {

    /** Platform payments this close to the period edges count as inside it (clock differences). */
    static final Duration EDGE = Duration.ofMinutes(10);

    /** A matched payment counts as uncredited only after this long (billing's P4 guard uses the same). */
    static final Duration CREDIT_DEADLINE = Duration.ofMinutes(5);

    private static final List<DateTimeFormatter> TIMES = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));

    public record StatementLine(String receipt, Instant completedAt, long paidInMinor, String details) {}

    public record ExtraPayment(UUID paymentId, String reference, long amountMinor, Instant receivedAt) {}

    public record Report(List<StatementLine> missing, List<ExtraPayment> extra, List<ExtraPayment> uncredited) {}

    public record RunDto(UUID id, Instant periodFrom, Instant periodTo, int statementRows, int missingCount,
                         int extraCount, int uncreditedCount, Report report, Instant createdAt) {}

    private final PaymentRepository payments;
    private final LedgerCreditCheck ledger;
    private final MpesaSettings settings;
    private final DarajaClient daraja;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    ReconciliationService(
            final PaymentRepository payments,
            final LedgerCreditCheck ledger,
            final MpesaSettings settings,
            final DarajaClient daraja,
            final AuditTrail audit,
            final JdbcClient jdbc,
            final JsonMapper json,
            final Clock clock) {
        this.payments = payments;
        this.ledger = ledger;
        this.settings = settings;
        this.daraja = daraja;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public RunDto importStatement(final String csv) {
        final List<StatementLine> lines = parse(csv);
        if (lines.isEmpty()) {
            throw InvalidRequestException.of("file", "no completed money-in rows");
        }
        final Instant from = lines.stream().map(StatementLine::completedAt).min(Instant::compareTo).orElseThrow();
        final Instant to = lines.stream().map(StatementLine::completedAt).max(Instant::compareTo).orElseThrow();

        final Set<String> onStatement = new HashSet<>();
        lines.forEach(line -> onStatement.add(line.receipt()));
        final Set<String> recorded = new HashSet<>(payments.findMpesaReferencesBetween(from.minus(EDGE), to.plus(EDGE)));
        final List<StatementLine> missing = lines.stream()
                .filter(line -> !recorded.contains(line.receipt()) && payments.findByProviderTxnId(line.receipt()).isEmpty())
                .toList();
        final List<ExtraPayment> extra = payments.findMpesaPaymentsBetween(from, to).stream()
                .filter(p -> !onStatement.contains(p.getProviderTxnId()))
                .map(ReconciliationService::extra)
                .toList();
        final List<ExtraPayment> uncredited = payments.findMatchedReceivedBetween(from.minus(EDGE), to.plus(EDGE)).stream()
                // Same grace as billing's alert: a payment matched minutes ago may still be on its way.
                .filter(p -> p.getMatchedAt().isBefore(clock.instant().minus(CREDIT_DEADLINE)))
                .filter(p -> !ledger.isCredited(p.getId()))
                .map(ReconciliationService::extra)
                .toList();

        final Report report = new Report(missing, extra, uncredited);
        final UUID id = UuidV7.next(clock);
        final Instant now = clock.instant();
        jdbc.sql("""
                        insert into payments_reconciliation_run
                            (id, tenant_id, source, period_from, period_to, statement_rows, missing_count, extra_count,
                             uncredited_count, report, created_by, created_at)
                        values (:id, :tenantId, 'STATEMENT_CSV', :from, :to, :rows, :missing, :extra, :uncredited,
                                cast(:report as jsonb), :createdBy, :now)
                        """)
                .param("id", id)
                .param("tenantId", TenantContext.requireTenantId())
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("rows", lines.size())
                .param("missing", missing.size())
                .param("extra", extra.size())
                .param("uncredited", uncredited.size())
                .param("report", json.writeValueAsString(report))
                .param("createdBy", CurrentActor.staffId().orElse(null))
                .param("now", Timestamp.from(now))
                .update();
        audit.record(AuditEntry.of("payments.reconciled", "reconciliation_run", id,
                Map.of("missing", missing.size(), "extra", extra.size(), "uncredited", uncredited.size())));
        return new RunDto(id, from, to, lines.size(), missing.size(), extra.size(), uncredited.size(), report, now);
    }

    @Transactional(readOnly = true)
    public RunDto get(final UUID id) {
        return runs("where id = :id", Map.of("id", id), 1, 0).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Reconciliation run"));
    }

    @Transactional(readOnly = true)
    public PageResponse<RunDto> list(final Pageable page) {
        final List<RunDto> items = runs("", Map.of(), page.getPageSize(), page.getOffset());
        final long total = jdbc.sql("select count(*) from payments_reconciliation_run").query(Long.class).single();
        return PageResponse.of(new PageImpl<>(items, page, total), run -> run);
    }

    /** Asks Daraja about a transaction the statement shows but the platform never received. */
    public String investigate(final String transactionId) {
        if (transactionId == null || !transactionId.strip().matches("[A-Z0-9]{8,20}")) {
            throw InvalidRequestException.of("transactionId", "an M-Pesa transaction ID such as QKA81LK5CY");
        }
        final DarajaClient.Credentials credentials = settings.credentials();
        if (credentials.initiatorName() == null || credentials.securityCredential() == null) {
            throw InvalidRequestException.of("settings", "the M-Pesa initiator name and security credential are not set");
        }
        return daraja.transactionStatus(credentials, transactionId.strip(),
                settings.callbackUrl("txn-status/result"), settings.callbackUrl("txn-status/timeout")).conversationId();
    }

    private List<RunDto> runs(final String where, final Map<String, Object> params, final int limit, final long offset) {
        var spec = jdbc.sql("""
                select id, period_from, period_to, statement_rows, missing_count, extra_count, uncredited_count,
                       report::text, created_at
                from payments_reconciliation_run %s
                order by created_at desc limit :limit offset :offset
                """.formatted(where))
                .param("limit", limit)
                .param("offset", offset);
        for (final Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        return spec.query((rs, n) -> new RunDto(
                        rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        json.readValue(rs.getString(8), Report.class), rs.getTimestamp(9).toInstant()))
                .list();
    }

    private static ExtraPayment extra(final Payment p) {
        return new ExtraPayment(p.getId(), p.getProviderTxnId(), p.getAmountMinor(), p.getReceivedAt());
    }

    static List<StatementLine> parse(final String csv) {
        final List<List<String>> records;
        try {
            records = CsvReader.read(csv == null ? "" : csv);
        } catch (IllegalArgumentException e) {
            throw InvalidRequestException.of("file", "not valid CSV: " + e.getMessage());
        }
        // Portal exports start with a few title lines; the header is the first row naming the receipt.
        int headerRow = -1;
        for (int i = 0; i < Math.min(records.size(), 20); i++) {
            if (column(records.get(i), "receipt no.", "receipt no", "receiptno", "transaction id").isPresent()) {
                headerRow = i;
                break;
            }
        }
        if (headerRow < 0) {
            throw InvalidRequestException.of("file", "no header with a 'Receipt No.' column");
        }
        final List<String> header = records.get(headerRow);
        final int receipt = column(header, "receipt no.", "receipt no", "receiptno", "transaction id").orElseThrow();
        final int paidIn = column(header, "paid in", "paid in (kes)", "credit").orElseThrow(
                () -> InvalidRequestException.of("file", "no 'Paid In' column"));
        final Optional<Integer> time = column(header, "completion time", "completed time", "initiation time", "date");
        final Optional<Integer> status = column(header, "transaction status", "status");
        final Optional<Integer> details = column(header, "details", "other party info", "opposite party");

        final List<StatementLine> lines = new ArrayList<>();
        final List<Violation> problems = new ArrayList<>();
        for (int i = headerRow + 1; i < records.size(); i++) {
            final List<String> row = records.get(i);
            final int number = i - headerRow;
            final String amountText = cell(row, paidIn).replace(",", "");
            if (amountText.isBlank() || cell(row, receipt).isBlank()) {
                continue; // money out, or a blank or summary line
            }
            if (status.isPresent() && !cell(row, status.get()).equalsIgnoreCase("Completed")) {
                continue;
            }
            final long minor;
            try {
                minor = new BigDecimal(amountText).movePointRight(2).longValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                problems.add(new Violation(number, "Paid In", "not an amount"));
                continue;
            }
            if (minor <= 0) {
                continue;
            }
            final Instant at = time.map(t -> parseTime(cell(row, t))).orElse(null);
            if (at == null) {
                problems.add(new Violation(number, "Completion Time", "not a date and time"));
                continue;
            }
            lines.add(new StatementLine(cell(row, receipt).strip().toUpperCase(Locale.ROOT), at, minor,
                    details.map(d -> cell(row, d)).orElse(null)));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems.subList(0, Math.min(problems.size(), 200)));
        }
        return lines;
    }

    private static Instant parseTime(final String text) {
        for (final DateTimeFormatter format : TIMES) {
            try {
                return LocalDateTime.parse(text.strip(), format).atZone(DarajaClient.EAT).toInstant();
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return null;
    }

    private static Optional<Integer> column(final List<String> header, final String... names) {
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.putIfAbsent(header.get(i).strip().toLowerCase(Locale.ROOT), i);
        }
        for (final String name : names) {
            if (index.containsKey(name)) {
                return Optional.of(index.get(name));
            }
        }
        return Optional.empty();
    }

    private static String cell(final List<String> row, final int index) {
        return index < row.size() ? row.get(index) : "";
    }
}
