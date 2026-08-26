package com.gpstore.config;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Server-side page-size cap shared by admin list endpoints.
 *
 * A cap the caller chooses is not a cap: size=100000 would still dump the
 * table. Every list that used to call findAll() goes through here.
 */
public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {}

    public static Pageable of(int page, int size) {
        return of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }

    public static Pageable of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }
}
