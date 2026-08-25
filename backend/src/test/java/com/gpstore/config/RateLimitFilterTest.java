package com.gpstore.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private StringRedisTemplate redis;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        filter = new RateLimitFilter(redis, false, 20, 20, 60, 30, 60);
    }

    @Test
    void catalogBrowseIsNotRateLimited() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/products/category/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void searchIsLimitedAndFailsOpenWhenRedisIsDown() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("Redis unavailable"));

        MockHttpServletRequest request = request("GET", "/api/products/search/instant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "a Redis outage must not take search down");
    }

    @Test
    void searchOverLimitReturns429() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(61L);

        MockHttpServletRequest request = request("GET", "/api/products/search/instant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void refreshAndLogoutAreLimited() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(21L);

        MockHttpServletRequest refresh = request("POST", "/api/auth/refresh");
        MockHttpServletResponse refreshResponse = new MockHttpServletResponse();
        filter.doFilter(refresh, refreshResponse, new MockFilterChain());
        assertEquals(429, refreshResponse.getStatus());

        MockHttpServletRequest logout = request("POST", "/api/auth/logout");
        MockHttpServletResponse logoutResponse = new MockHttpServletResponse();
        filter.doFilter(logout, logoutResponse, new MockFilterChain());
        assertEquals(429, logoutResponse.getStatus());
    }

    @Test
    void searchUnderLimitIsAllowed() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        MockHttpServletRequest request = request("GET", "/api/products/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRequestURI("/v1" + path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }
}
