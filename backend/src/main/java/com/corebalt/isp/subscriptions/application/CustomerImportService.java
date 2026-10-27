package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.customers.api.CustomerDto;
import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerRequest;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageSummary;
import com.corebalt.isp.packages.api.PackageCatalog.PackageTerms;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.csv.CsvReader;
import com.corebalt.isp.shared.id.UuidV7;
import com.corebalt.isp.shared.money.Money;
import com.corebalt.isp.shared.money.MoneyDto;
import com.corebalt.isp.shared.security.CurrentActor;
import com.corebalt.isp.shared.security.Permissions;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.InvalidRequestException.Violation;
import com.corebalt.isp.subscriptions.api.CustomerImportPreviewDto;
import com.corebalt.isp.subscriptions.api.CustomerImportPreviewDto.Activation;
import com.corebalt.isp.subscriptions.api.CustomerImportPreviewDto.Warning;
import com.corebalt.isp.subscriptions.api.CustomerImportResultDto;
import com.corebalt.isp.subscriptions.api.LineOpenedDto;
import com.corebalt.isp.subscriptions.api.RouterDirectory;
import com.corebalt.isp.subscriptions.application.LineService.KeptCredential;
import com.corebalt.isp.subscriptions.domain.PaidUntil;
import com.corebalt.isp.subscriptions.domain.PppoeUsernames;
import com.corebalt.isp.subscriptions.infrastructure.ImportBatches;
import com.corebalt.isp.tenancy.api.TenantProfile;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports customers from CSV during tenant onboarding (docs/07 checklist step 4, the pilot-onboarding
 * runbook): all rows or none. The whole file is validated first (phone numbers, duplicates in the
 * file and against existing customers, package names, credentials, references, dates); any error
 * rejects the file with every row error. A row with a {@code package} also gets a PPPoE line on
 * {@code routerId}: with a generated password, returned once in the result, or with the username
 * and password the customer already has in the ISP's old system (ADR 0014), never returned.
 *
 * <p>The cutover (S11, ADR 0014): a row with {@code paid_until} opens its line ACTIVE with that
 * expiry, no payment on the platform. Only while the tenant's cutover window is open, only for a
 * caller holding {@link Permissions#SUBSCRIPTIONS_IMPORT_ACTIVE}, at most one package duration
 * ahead, and only after a preview of this very file: {@link #preview} returns a confirmation that
 * {@link #importCsv} must get back. A date alone means {@code timeOfDay} in the tenant's zone.
 *
 * <p>Columns, header required, any order: {@code name}, {@code phone} (required), {@code email},
 * {@code address}, {@code installation_location}, {@code lat}, {@code lng}, {@code package},
 * {@code paid_until}, {@code pppoe_username}, {@code pppoe_password}, {@code legacy_account_ref}.
 * Row contents are never logged (A3); a password never appears in an error message.
 */
@Service
public class CustomerImportService {

    public static final int MAX_ROWS = 5000;
    public static final LocalTime DEFAULT_TIME_OF_DAY = LocalTime.NOON;

    static final Set<String> COLUMNS = Set.of(
            "name", "phone", "email", "address", "installation_location", "lat", "lng", "package",
            "paid_until", "pppoe_username", "pppoe_password", "legacy_account_ref");

    /** More dated rows than this share already in the past usually means DD/MM read as MM/DD. */
    static final double PAST_SHARE_WARNING = 0.10;
    /** With at least this many dated rows, more than this share on days 1 to 12 means the same. */
    static final int CLUSTER_MIN_ROWS = 10;
    static final double CLUSTER_SHARE_WARNING = 0.90;

    private static final int MAX_REPORTED_ERRORS = 200;
    private static final Pattern PASSWORD = Pattern.compile("[\\x20-\\x7E]{1,64}");
    private static final Pattern SHORT_NUMBER = Pattern.compile("[0-9]{1,6}");

    private final CustomerFacade customers;
    private final PackageCatalog packages;
    private final RouterDirectory routers;
    private final LineService lines;
    private final LifecycleService lifecycle;
    private final TenantProfile tenantProfile;
    private final ImportBatches batches;
    private final AuditTrail audit;
    private final Validator validator;
    private final Clock clock;

    CustomerImportService(
            final CustomerFacade customers,
            final PackageCatalog packages,
            final RouterDirectory routers,
            final LineService lines,
            final LifecycleService lifecycle,
            final TenantProfile tenantProfile,
            final ImportBatches batches,
            final AuditTrail audit,
            final Validator validator,
            final Clock clock) {
        this.customers = customers;
        this.packages = packages;
        this.routers = routers;
        this.lines = lines;
        this.lifecycle = lifecycle;
        this.tenantProfile = tenantProfile;
        this.batches = batches;
        this.audit = audit;
        this.validator = validator;
        this.clock = clock;
    }

    /** The dry run: everything the import checks, and the totals, with nothing written. */
    @Transactional(readOnly = true)
    public CustomerImportPreviewDto preview(final String csv, final UUID routerId, final LocalTime timeOfDay) {
        return parseAndValidate(csv, routerId, timeOfDay).summary();
    }

    @Transactional
    public CustomerImportResultDto importCsv(final String csv, final UUID routerId, final LocalTime timeOfDay,
                                             final String confirmation) {
        CurrentActor.requireNotImpersonating("Importing customers (it sets line passwords)"); // ADR 0017: secrets stay with the tenant
        final Parsed parsed = parseAndValidate(csv, routerId, timeOfDay);
        final CustomerImportPreviewDto summary = parsed.summary();
        if (summary.activation().total() > 0 && !summary.confirmation().equals(confirmation)) {
            throw InvalidRequestException.of("confirmation",
                    "an import with paid_until is previewed first: send back the confirmation the preview returned"
                            + " for this file and these options (S11)");
        }
        final Instant now = clock.instant();
        final UUID batchId = UuidV7.next(clock);
        batches.create(batchId, routerId, CurrentActor.staffId().orElse(null), summary, now);
        final List<CustomerImportResultDto.Row> result = new ArrayList<>(parsed.rows().size());
        for (final ParsedRow row : parsed.rows()) {
            final CustomerDto customer = customers.register(row.request());
            if (row.pkg() == null) {
                batches.addRow(batchId, row.number(), customer.id(), null);
                result.add(new CustomerImportResultDto.Row(
                        row.number(), customer.id(), customer.number(), customer.name(), null, null, null, false, null));
                continue;
            }
            final LineOpenedDto line = lines.open(
                    new CustomerSummary(customer.id(), customer.number(), customer.name(), true), row.pkg(), routerId,
                    row.kept());
            Instant activatedUntil = null;
            if (row.paidUntil() != null) {
                activatedUntil = lifecycle.activateByImport(line.subscription().id(), row.paidUntil(), row.terms(), batchId,
                        row.number()).expiresAt();
            }
            batches.addRow(batchId, row.number(), customer.id(), line.subscription().id());
            result.add(new CustomerImportResultDto.Row(
                    row.number(),
                    customer.id(),
                    customer.number(),
                    customer.name(),
                    line.subscription().id(),
                    line.subscription().pppoeUsername(),
                    line.pppoePassword(),
                    row.kept() != null,
                    activatedUntil));
        }
        audit.record(new AuditEntry("subscriptions.customers_imported", "import_batch", batchId, null,
                ImportBatches.auditDetail(summary), null));
        return new CustomerImportResultDto(batchId, result.size(), summary.activation().total(), result);
    }

    private Parsed parseAndValidate(final String csv, final UUID routerId, final LocalTime timeOfDay) {
        final LocalTime expiryTime = timeOfDay == null ? DEFAULT_TIME_OF_DAY : timeOfDay;
        final ZoneId zone = tenantProfile.currentPolicy().zone();
        final Instant now = clock.instant();
        final List<ParsedRow> rows = validate(parse(csv), routerId, expiryTime, zone, now);
        return new Parsed(rows, summarise(rows, now, zone, confirmation(csv, routerId, expiryTime)));
    }

    private static List<Map<String, String>> parse(final String csv) {
        final List<List<String>> records;
        try {
            records = CsvReader.read(csv == null ? "" : csv);
        } catch (IllegalArgumentException e) {
            throw InvalidRequestException.of("file", "not valid CSV: " + e.getMessage());
        }
        if (records.isEmpty()) {
            throw InvalidRequestException.of("file", "empty; the first line must name the columns");
        }
        final List<String> header =
                records.getFirst().stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        final List<Violation> problems = new ArrayList<>();
        for (final String column : header) {
            if (!COLUMNS.contains(column)) {
                problems.add(new Violation(0, column, "unknown column; expected " + String.join(", ", sorted(COLUMNS))));
            }
        }
        if (header.stream().distinct().count() != header.size()) {
            problems.add(new Violation(0, "header", "a column appears twice"));
        }
        for (final String required : List.of("name", "phone")) {
            if (!header.contains(required)) {
                problems.add(new Violation(0, required, "column is required"));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        if (records.size() - 1 > MAX_ROWS) {
            throw InvalidRequestException.of("file", "at most " + MAX_ROWS + " rows per import");
        }
        if (records.size() == 1) {
            throw InvalidRequestException.of("file", "no data rows");
        }
        final List<Map<String, String>> rows = new ArrayList<>(records.size() - 1);
        for (int i = 1; i < records.size(); i++) {
            final List<String> record = records.get(i);
            final Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.size(); c++) {
                final String value = c < record.size() ? record.get(c).strip() : "";
                row.put(header.get(c), value.isEmpty() ? null : value);
            }
            if (record.size() > header.size()) {
                row.put("_extra", "true");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<ParsedRow> validate(final List<Map<String, String>> rows, final UUID routerId, final LocalTime timeOfDay,
                                     final ZoneId zone, final Instant now) {
        final List<Violation> problems = new ArrayList<>();
        final List<ParsedRow> parsed = new ArrayList<>(rows.size());
        final Map<String, Integer> phoneFirstRow = new LinkedHashMap<>();
        final Map<String, Integer> usernameFirstRow = new LinkedHashMap<>();
        final Map<String, Integer> legacyRefFirstRow = new LinkedHashMap<>();
        final Map<String, Optional<PackageSummary>> packageCache = new HashMap<>();
        final Map<UUID, Optional<PackageTerms>> termsCache = new HashMap<>();
        boolean needsRouter = false;
        boolean activates = false;

        for (int i = 0; i < rows.size(); i++) {
            final int rowNumber = i + 1;
            final Map<String, String> row = rows.get(i);
            if (row.containsKey("_extra")) {
                problems.add(new Violation(rowNumber, "row", "more values than columns"));
            }
            final BigDecimal lat = decimal(row.get("lat"), rowNumber, "lat", problems);
            final BigDecimal lng = decimal(row.get("lng"), rowNumber, "lng", problems);
            final String legacyRef = legacyRef(row.get("legacy_account_ref"), rowNumber, legacyRefFirstRow, problems);
            final CustomerRequest request =
                    new CustomerRequest(
                            row.get("name"),
                            row.get("phone"),
                            row.get("email"),
                            row.get("address"),
                            row.get("installation_location"),
                            lat,
                            lng,
                            legacyRef);
            validator.validate(request).forEach(v ->
                    problems.add(new Violation(rowNumber, column(v.getPropertyPath().toString()), v.getMessage())));

            if (row.get("phone") != null) {
                final Optional<String> phone = customers.normalisePhone(row.get("phone"));
                if (phone.isEmpty()) {
                    problems.add(new Violation(rowNumber, "phone", "not a valid phone number"));
                } else {
                    final Integer first = phoneFirstRow.putIfAbsent(phone.get(), rowNumber);
                    if (first != null) {
                        problems.add(new Violation(rowNumber, "phone", "same number as row " + first));
                    }
                }
            }

            PackageSummary pkg = null;
            final String packageName = row.get("package");
            if (packageName != null) {
                needsRouter = true;
                pkg = packageCache.computeIfAbsent(packageName.toLowerCase(Locale.ROOT), k -> packages.findByName(packageName))
                        .orElse(null);
                if (pkg == null) {
                    problems.add(new Violation(rowNumber, "package", "no package named '" + packageName + "'"));
                } else if (!pkg.sellableAsPppoe()) {
                    problems.add(new Violation(rowNumber, "package", "'" + pkg.name() + "' is not an active PPPoE package"));
                }
            }

            final KeptCredential kept = kept(row, rowNumber, packageName != null, usernameFirstRow, problems);

            Instant paidUntil = null;
            PackageTerms terms = null;
            final String paidUntilValue = row.get("paid_until");
            if (paidUntilValue != null) {
                activates = true;
                if (packageName == null) {
                    problems.add(new Violation(rowNumber, "paid_until", "needs a package"));
                }
                if (pkg != null) {
                    terms = termsCache.computeIfAbsent(pkg.id(), packages::terms).orElse(null);
                }
                final Optional<Instant> date = PaidUntil.parse(paidUntilValue, zone, timeOfDay);
                if (date.isEmpty()) {
                    problems.add(new Violation(rowNumber, "paid_until", "not a date: use " + PaidUntil.RULE));
                } else if (terms != null && date.get().isAfter(now.plus(Duration.ofMinutes(terms.durationMinutes())))) {
                    problems.add(new Violation(rowNumber, "paid_until", "more than one package duration ahead; at most "
                            + now.plus(Duration.ofMinutes(terms.durationMinutes())) + " (S11)"));
                } else if (terms != null) {
                    paidUntil = date.get();
                }
            }
            parsed.add(new ParsedRow(rowNumber, request, pkg, terms, kept, paidUntil, legacyRef));
        }

        final Set<String> taken = customers.existingPhones(phoneFirstRow.keySet());
        phoneFirstRow.forEach((phone, rowNumber) -> {
            if (taken.contains(phone)) {
                problems.add(new Violation(rowNumber, "phone", "already belongs to a customer"));
            }
        });
        final Set<String> usernamesTaken = lines.existingUsernames(usernameFirstRow.keySet());
        usernameFirstRow.forEach((username, rowNumber) -> {
            if (usernamesTaken.contains(username)) {
                problems.add(new Violation(rowNumber, "pppoe_username", "already used by a line"));
            }
        });
        final Set<String> legacyTaken = customers.existingLegacyAccountRefs(legacyRefFirstRow.keySet());
        legacyRefFirstRow.forEach((reference, rowNumber) -> {
            if (legacyTaken.contains(reference)) {
                problems.add(new Violation(rowNumber, "legacy_account_ref", "already belongs to a customer"));
            }
        });

        if (needsRouter && routerId == null) {
            problems.add(new Violation(null, "routerId", "required when rows have a package"));
        } else if (needsRouter && !routers.exists(routerId)) {
            problems.add(new Violation(null, "routerId", "no such router, or it is retired"));
        }
        if (activates) {
            if (!CurrentActor.has(Permissions.SUBSCRIPTIONS_IMPORT_ACTIVE)) {
                problems.add(new Violation(null, "paid_until",
                        "activating imported lines needs the " + Permissions.SUBSCRIPTIONS_IMPORT_ACTIVE
                                + " permission (the Owner, with 2FA; S11)"));
            }
            if (tenantProfile.currentCutoverWindowUntil().filter(now::isBefore).isEmpty()) {
                problems.add(new Violation(null, "paid_until",
                        "the cutover window is closed: a Corebalt platform admin opens it (ADR 0014)"));
            }
        }

        if (!problems.isEmpty()) {
            problems.sort((a, b) -> Integer.compare(
                    a.row() == null ? -1 : a.row(), b.row() == null ? -1 : b.row()));
            throw new InvalidRequestException(problems.subList(0, Math.min(problems.size(), MAX_REPORTED_ERRORS)));
        }
        return parsed;
    }

    /** Both credential columns or neither; the username in the kept shape and unique; the password never echoed. */
    private static KeptCredential kept(final Map<String, String> row, final int rowNumber, final boolean hasPackage,
                                       final Map<String, Integer> usernameFirstRow, final List<Violation> problems) {
        final String username = row.get("pppoe_username");
        final String password = row.get("pppoe_password");
        if (username == null && password == null) {
            return null;
        }
        if (username == null || password == null) {
            problems.add(new Violation(rowNumber, username == null ? "pppoe_username" : "pppoe_password",
                    "pppoe_username and pppoe_password go together"));
            return null;
        }
        boolean ok = true;
        if (!hasPackage) {
            problems.add(new Violation(rowNumber, "pppoe_username", "needs a package"));
            ok = false;
        }
        final Optional<String> problem = PppoeUsernames.keptProblem(username);
        if (problem.isPresent()) {
            problems.add(new Violation(rowNumber, "pppoe_username", problem.get()));
            ok = false;
        } else {
            final Integer first = usernameFirstRow.putIfAbsent(username, rowNumber);
            if (first != null) {
                problems.add(new Violation(rowNumber, "pppoe_username", "same username as row " + first));
                ok = false;
            }
        }
        if (!PASSWORD.matcher(password).matches()) {
            problems.add(new Violation(rowNumber, "pppoe_password", "1 to 64 printable characters"));
            ok = false;
        }
        return ok ? new KeptCredential(username, password) : null;
    }

    private String legacyRef(final String raw, final int rowNumber, final Map<String, Integer> firstRow,
                             final List<Violation> problems) {
        if (raw == null) {
            return null;
        }
        final Optional<String> normalised = customers.normaliseLegacyAccountRef(raw);
        if (normalised.isEmpty()) {
            problems.add(new Violation(rowNumber, "legacy_account_ref", "1 to 32 letters, digits, spaces or . _ / # -"));
            return null;
        }
        final Integer first = firstRow.putIfAbsent(normalised.get(), rowNumber);
        if (first != null) {
            problems.add(new Violation(rowNumber, "legacy_account_ref", "same reference as row " + first));
        }
        return normalised.get();
    }

    private static CustomerImportPreviewDto summarise(final List<ParsedRow> rows, final Instant now, final ZoneId zone,
                                                      final String confirmation) {
        int lineCount = 0;
        int kept = 0;
        int legacy = 0;
        int shortLegacy = 0;
        int activating = 0;
        int expired = 0;
        int clustered = 0;
        int withoutCredentials = 0;
        Instant earliest = null;
        Instant latest = null;
        Money value = null;
        for (final ParsedRow row : rows) {
            if (row.pkg() != null) {
                lineCount++;
            }
            if (row.kept() != null) {
                kept++;
            }
            if (row.legacyRef() != null) {
                legacy++;
                if (SHORT_NUMBER.matcher(row.legacyRef()).matches()) {
                    shortLegacy++;
                }
            }
            final Instant paidUntil = row.paidUntil();
            if (paidUntil == null) {
                continue;
            }
            activating++;
            if (paidUntil.isAfter(now)) {
                final long minutesAhead = Duration.between(now, paidUntil).toMinutes();
                final Money granted =
                        row.terms().price().multiplyRatioRoundUpToMajor(minutesAhead, row.terms().durationMinutes());
                value = value == null ? granted : value.plus(granted);
            } else {
                expired++;
            }
            if (paidUntil.atZone(zone).getDayOfMonth() <= 12) {
                clustered++;
            }
            if (row.kept() == null) {
                withoutCredentials++;
            }
            earliest = earliest == null || paidUntil.isBefore(earliest) ? paidUntil : earliest;
            latest = latest == null || paidUntil.isAfter(latest) ? paidUntil : latest;
        }
        final List<Warning> warnings = new ArrayList<>();
        if (activating > 0 && expired > PAST_SHARE_WARNING * activating) {
            warnings.add(new Warning("PAST_SHARE", expired + " of " + activating + " dated rows are already in the past;"
                    + " a swapped day and month (DD/MM read as MM/DD) usually looks like this", expired));
        }
        if (activating >= CLUSTER_MIN_ROWS && clustered > CLUSTER_SHARE_WARNING * activating) {
            warnings.add(new Warning("DAY_CLUSTER", clustered + " of " + activating + " dates fall on days 1 to 12 of a month;"
                    + " a swapped day and month (DD/MM read as MM/DD) usually looks like this", clustered));
        }
        if (shortLegacy > 0) {
            warnings.add(new Warning("SHORT_LEGACY_REFS", shortLegacy + " old account references are 1 to 6 digits: they"
                    + " shadow the short form of customer numbers in M-Pesa matching (P3), so tell these customers to use"
                    + " their new number and clear the old reference later", shortLegacy));
        }
        if (withoutCredentials > 0) {
            warnings.add(new Warning("ACTIVE_WITHOUT_CREDENTIALS", withoutCredentials + " activating rows keep no PPPoE"
                    + " credentials: those customers' routers need the new username and password before they can log in",
                    withoutCredentials));
        }
        final Activation activation = new Activation(activating, expired, activating - expired, earliest, latest,
                value == null ? null : MoneyDto.of(value));
        return new CustomerImportPreviewDto(rows.size(), lineCount, kept, legacy, activation, List.copyOf(warnings),
                confirmation);
    }

    /** The preview and the import see the same file and options, or the confirmation does not match. */
    static String confirmation(final String csv, final UUID routerId, final LocalTime timeOfDay) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8));
            digest.update(("\n" + routerId + "\n" + timeOfDay).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal decimal(
            final String value, final int rowNumber, final String field, final List<Violation> problems) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            problems.add(new Violation(rowNumber, field, "not a number"));
            return null;
        }
    }

    /** Bean Validation property names back to CSV column names. */
    private static String column(final String property) {
        return switch (property) {
            case "installationLocation" -> "installation_location";
            case "legacyAccountRef" -> "legacy_account_ref";
            default -> property;
        };
    }

    private static List<String> sorted(final Set<String> values) {
        return values.stream().sorted().toList();
    }

    private record Parsed(List<ParsedRow> rows, CustomerImportPreviewDto summary) {}

    /** {@code terms} is loaded only for a row with {@code paid_until}: the cap, the value granted and the activation need it. */
    private record ParsedRow(int number, CustomerRequest request, PackageSummary pkg, PackageTerms terms,
                             KeptCredential kept, Instant paidUntil, String legacyRef) {}
}
