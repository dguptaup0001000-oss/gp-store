package com.gpstore.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThreadPoolSaturationFilterTest {

    @Test
    void catalogGetIsShedWhenEveryWorkerIsBusy() throws Exception {
        ThreadPoolSaturationFilter filter = filterWith(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/products/feed");
        request.setRequestURI("/v1/api/products/feed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertEquals("thread-pool-saturated", response.getHeader("X-GP-Shed"));
        assertNull(chain.getRequest());
    }

    @Test
    void catalogGetPassesWhenWorkersAreFree() throws Exception {
        ThreadPoolSaturationFilter filter = filterWith(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/products/feed");
        request.setRequestURI("/v1/api/products/feed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void liveHealthIsNeverShed() throws Exception {
        ThreadPoolSaturationFilter filter = filterWith(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/api/health/live");
        request.setRequestURI("/v1/api/health/live");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }

    private static ThreadPoolSaturationFilter filterWith(boolean saturated) {
        TomcatRequestCapacity capacity = mock(TomcatRequestCapacity.class);
        when(capacity.isMainPoolSaturated()).thenReturn(saturated);
        return new ThreadPoolSaturationFilter(
                capacity,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}
