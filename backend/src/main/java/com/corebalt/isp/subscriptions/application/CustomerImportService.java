package com.corebalt.isp.subscriptions.application;

import com.corebalt.isp.customers.api.CustomerDto;
import com.corebalt.isp.customers.api.CustomerFacade;
import com.corebalt.isp.customers.api.CustomerRequest;
import com.corebalt.isp.customers.api.CustomerSummary;
import com.corebalt.isp.packages.api.PackageCatalog;
import com.corebalt.isp.packages.api.PackageCatalog.PackageSummary;
import com.corebalt.isp.shared.csv.CsvReader;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.InvalidRequestException.Violation;
import com.corebalt.isp.subscriptions.api.CustomerImportResultDto;
import com.corebalt.isp.subscriptions.api.LineOpenedDto;
import com.corebalt.isp.subscriptions.api.RouterDirectory;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports customers from CSV during tenant onboarding (docs/07 checklist step 4): all rows or none.
 * The whole file is validated first (phone numbers, duplicates in the file and against existing
 * customers, package names); any error rejects the file with every row error. A row with a
 * {@code package} also gets a PENDING PPPoE line on {@code routerId} with a generated password,
 * returned once in the result. Activation and current expiry dates are not imported: the lifecycle
 * is step 4. Row contents are never logged (A3).
 *
 * <p>Columns, header required, any order: {@code name}, {@code phone} (required), {@code email},
 * {@code address}, {@code installation_location}, {@code lat}, {@code lng}, {@code package}.
 */
@Service
public class CustomerImportService {

    public static final int MAX_ROWS = 5000;

    static final Set<String> COLUMNS =
            Set.of("name", "phone", "email", "address", "installation_location", "lat", "lng", "package");

    private static final int MAX_REPORTED_ERRORS = 200;

    private final CustomerFacade customers;
    private final PackageCatalog packages;
    private final RouterDirectory routers;
    private final LineService lines;
    private final Validator validator;

    CustomerImportService(
            final CustomerFacade customers,
            final PackageCatalog packages,
            final RouterDirectory routers,
            final LineService lines,
            final Validator validator) {
        this.customers = customers;
        this.packages = packages;
        this.routers = routers;
        this.lines = lines;
        this.validator = validator;
    }

    @Transactional
    public CustomerImportResultDto importCsv(final String csv, final UUID routerId) {
        final List<ParsedRow> rows = validate(parse(csv), routerId);
        final List<CustomerImportResultDto.Row> result = new ArrayList<>(rows.size());
        for (final ParsedRow row : rows) {
            final CustomerDto customer = customers.register(row.request());
            if (row.pkg() == null) {
                result.add(new CustomerImportResultDto.Row(
                        row.number(), customer.id(), customer.number(), customer.name(), null, null, null));
                continue;
            }
            final LineOpenedDto line =
                    lines.open(
                            new CustomerSummary(customer.id(), customer.number(), customer.name(), true),
                            row.pkg(),
                            routerId);
            result.add(new CustomerImportResultDto.Row(
                    row.number(),
                    customer.id(),
                    customer.number(),
                    customer.name(),
                    line.subscription().id(),
                    line.subscription().pppoeUsername(),
                    line.pppoePassword()));
        }
        return new CustomerImportResultDto(result.size(), result);
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

    private List<ParsedRow> validate(final List<Map<String, String>> rows, final UUID routerId) {
        final List<Violation> problems = new ArrayList<>();
        final List<ParsedRow> parsed = new ArrayList<>(rows.size());
        final Map<String, Integer> phoneFirstRow = new LinkedHashMap<>();
        final Map<String, Optional<PackageSummary>> packageCache = new HashMap<>();
        boolean needsRouter = false;

        for (int i = 0; i < rows.size(); i++) {
            final int rowNumber = i + 1;
            final Map<String, String> row = rows.get(i);
            if (row.containsKey("_extra")) {
                problems.add(new Violation(rowNumber, "row", "more values than columns"));
            }
            final BigDecimal lat = decimal(row.get("lat"), rowNumber, "lat", problems);
            final BigDecimal lng = decimal(row.get("lng"), rowNumber, "lng", problems);
            final CustomerRequest request =
                    new CustomerRequest(
                            row.get("name"),
                            row.get("phone"),
                            row.get("email"),
                            row.get("address"),
                            row.get("installation_location"),
                            lat,
                            lng);
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
            parsed.add(new ParsedRow(rowNumber, request, pkg));
        }

        final Set<String> taken = customers.existingPhones(phoneFirstRow.keySet());
        phoneFirstRow.forEach((phone, rowNumber) -> {
            if (taken.contains(phone)) {
                problems.add(new Violation(rowNumber, "phone", "already belongs to a customer"));
            }
        });

        if (needsRouter && routerId == null) {
            problems.add(new Violation(null, "routerId", "required when rows have a package"));
        } else if (needsRouter && !routers.exists(routerId)) {
            problems.add(new Violation(null, "routerId", "no such router"));
        }

        if (!problems.isEmpty()) {
            problems.sort((a, b) -> Integer.compare(
                    a.row() == null ? -1 : a.row(), b.row() == null ? -1 : b.row()));
            throw new InvalidRequestException(problems.subList(0, Math.min(problems.size(), MAX_REPORTED_ERRORS)));
        }
        return parsed;
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
        return "installationLocation".equals(property) ? "installation_location" : property;
    }

    private static List<String> sorted(final Set<String> values) {
        return values.stream().sorted().toList();
    }

    private record ParsedRow(int number, CustomerRequest request, PackageSummary pkg) {}
}
