package com.gpstore.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogPublicCacheFilterTest {

    private final CatalogPublicCacheFilter filter = new CatalogPublicCacheFilter();

    @Test
    void feedIsPubliclyCacheable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/products/feed");
        request.setRequestURI("/v1/api/products/feed");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(CatalogPublicCacheFilter.PUBLIC_CATALOG, response.getHeader("Cache-Control"));
    }

    @Test
    void searchIsNotCached() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/products/search/instant");
        request.setRequestURI("/v1/api/products/search/instant");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(CatalogPublicCacheFilter.NO_STORE, response.getHeader("Cache-Control"));
    }

    @Test
    void checkoutIsNotCached() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/api/orders/place");
        request.setRequestURI("/v1/api/orders/place");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(CatalogPublicCacheFilter.NO_STORE, response.getHeader("Cache-Control"));
    }
}
