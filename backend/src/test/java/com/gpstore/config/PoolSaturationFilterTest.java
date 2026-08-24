package com.gpstore.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PoolSaturationFilterTest {

    @Test
    void catalogGetIsShedWhenThePoolAlreadyHasAQueue() throws Exception {
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(mx.getThreadsAwaitingConnection()).thenReturn(PoolSaturationFilter.WAITING_SHED_THRESHOLD);

        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getHikariPoolMXBean()).thenReturn(mx);

        PoolSaturationFilter filter = new PoolSaturationFilter(hikari);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/products");
        request.setRequestURI("/v1/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertNull(chain.getRequest());
    }

    @Test
    void checkoutIsNotShedByThisFilter() throws Exception {
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(mx.getThreadsAwaitingConnection()).thenReturn(20);

        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getHikariPoolMXBean()).thenReturn(mx);

        PoolSaturationFilter filter = new PoolSaturationFilter(hikari);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/api/orders/place");
        request.setRequestURI("/v1/api/orders/place");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }
}
