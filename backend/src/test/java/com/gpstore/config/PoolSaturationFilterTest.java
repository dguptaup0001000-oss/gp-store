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
    void catalogGetIsShedWhenThePoolIsFullAndQueued() throws Exception {
        PoolSaturationFilter filter = filterWith(10, 10, PoolSaturationFilter.WAITING_SHED_THRESHOLD);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/products");
        request.setRequestURI("/v1/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertEquals("pool-saturated", response.getHeader("X-GP-Shed"));
        assertNull(chain.getRequest());
    }

    @Test
    void waitingThreadsAloneDoNotShedWhileConnectionsAreStillIdle() throws Exception {
        PoolSaturationFilter filter = filterWith(4, 10, 20);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/products");
        request.setRequestURI("/v1/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void checkoutIsNotShedByThisFilter() throws Exception {
        PoolSaturationFilter filter = filterWith(10, 10, 20);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/api/orders/place");
        request.setRequestURI("/v1/api/orders/place");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }

    private static PoolSaturationFilter filterWith(int active, int maxPool, int waiting) {
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(mx.getThreadsAwaitingConnection()).thenReturn(waiting);
        when(mx.getActiveConnections()).thenReturn(active);

        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getHikariPoolMXBean()).thenReturn(mx);
        when(hikari.getMaximumPoolSize()).thenReturn(maxPool);
        return new PoolSaturationFilter(
                hikari,
                PoolSaturationFilter.WAITING_SHED_THRESHOLD,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}
