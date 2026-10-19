package com.corebalt.isp.shared.web;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a {@link Pageable} from the list parameters every endpoint takes: {@code page} (from 0),
 * {@code size} (1 to 200) and {@code sort} as {@code field} or {@code field,desc}, limited to the
 * fields an endpoint allows so clients cannot sort on unindexed or internal columns.
 */
public final class PageRequests {

    public static final int MAX_SIZE = 200;

    private PageRequests() {}

    public static Pageable of(
            final int page, final int size, final String sort, final Set<String> sortable, final Sort fallback) {
        if (page < 0) {
            throw InvalidRequestException.of("page", "must be 0 or more");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw InvalidRequestException.of("size", "must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(page, size, sort == null || sort.isBlank() ? fallback : parse(sort, sortable));
    }

    private static Sort parse(final String sort, final Set<String> sortable) {
        final String[] parts = sort.split(",", -1);
        final String field = parts[0].strip();
        if (!sortable.contains(field) || parts.length > 2) {
            throw InvalidRequestException.of("sort", "sortable fields: " + String.join(", ", sortable.stream().sorted().toList()));
        }
        final boolean descending = parts.length == 2 && parts[1].strip().equalsIgnoreCase("desc");
        if (parts.length == 2 && !descending && !parts[1].strip().equalsIgnoreCase("asc")) {
            throw InvalidRequestException.of("sort", "direction must be asc or desc");
        }
        return descending ? Sort.by(field).descending() : Sort.by(field).ascending();
    }
}
