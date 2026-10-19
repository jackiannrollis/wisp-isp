package com.corebalt.isp.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Paged list response: {@code items}, {@code page}, {@code size}, {@code total} (conventions.md, API). */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <E, T> PageResponse<T> of(final Page<E> page, final Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }
}
