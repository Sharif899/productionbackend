package com.sharif899.prodbackend.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** A page of results, with the paging metadata a client needs to walk it. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
