package com.corebalt.isp.shared.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 reader for staff imports: comma-separated, fields optionally in double quotes,
 * {@code ""} for a quote inside a quoted field, CRLF or LF line ends, an optional UTF-8 BOM. Blank
 * lines are skipped. Values are returned as written; callers trim and validate.
 */
public final class CsvReader {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    private CsvReader() {}

    public static List<List<String>> read(final String text) {
        final String input = text.startsWith(BOM) ? text.substring(1) : text;
        final List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        int i = 0;
        while (i < input.length()) {
            final char c = input.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < input.length() && input.charAt(i + 1) == '"') {
                    field.append('"');
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty() && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                if (!(row.size() == 1 && row.getFirst().isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                field.append(c);
                fieldStarted = true;
            }
            i++;
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        if (fieldStarted || !field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
