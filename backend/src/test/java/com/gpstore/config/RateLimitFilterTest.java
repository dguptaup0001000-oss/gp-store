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

    @Test
    void checkoutSessionAndVerifyAreRateLimited() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(21L);

        MockHttpServletRequest session = request("POST", "/api/payments/order/42/checkout-session");
        MockHttpServletResponse sessionResponse = new MockHttpServletResponse();
        filter.doFilter(session, sessionResponse, new MockFilterChain());
        assertEquals(429, sessionResponse.getStatus());

        MockHttpServletRequest verify = request("POST", "/api/payments/order/42/verify");
        MockHttpServletResponse verifyResponse = new MockHttpServletResponse();
        filter.doFilter(verify, verifyResponse, new MockFilterChain());
        assertEquals(429, verifyResponse.getStatus());

        MockHttpServletRequest place = request("POST", "/api/orders/place");
        MockHttpServletResponse placeResponse = new MockHttpServletResponse();
        filter.doFilter(place, placeResponse, new MockFilterChain());
        assertEquals(429, placeResponse.getStatus());

        MockHttpServletRequest refund = request("PUT", "/api/payments/order/42/refund");
        MockHttpServletResponse refundResponse = new MockHttpServletResponse();
        filter.doFilter(refund, refundResponse, new MockFilterChain());
        assertEquals(429, refundResponse.getStatus());

        MockHttpServletRequest coupon = request("GET", "/api/coupons/validate");
        MockHttpServletResponse couponResponse = new MockHttpServletResponse();
        filter.doFilter(coupon, couponResponse, new MockFilterChain());
        assertEquals(429, couponResponse.getStatus());
    }

    @Test
    void checkoutSessionUnderLimitIsAllowed() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(2L);

        MockHttpServletRequest request = request("POST", "/api/payments/order/7/checkout-session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void securitySensitiveLimitsFailClosedToLocalWhenRedisIsDown() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("Redis unavailable"));

        RateLimitFilter tight = new RateLimitFilter(redis, false, 2, 2, 60, 2, 60);

        MockFilterChain first = new MockFilterChain();
        tight.doFilter(request("POST", "/api/auth/login"), new MockHttpServletResponse(), first);
        assertNotNull(first.getRequest(), "the first login attempt during a Redis outage must still work");

        MockFilterChain second = new MockFilterChain();
        tight.doFilter(request("POST", "/api/auth/login"), new MockHttpServletResponse(), second);
        assertNotNull(second.getRequest(), "a second login during a Redis outage must still work");

        MockHttpServletResponse third = new MockHttpServletResponse();
        tight.doFilter(request("POST", "/api/auth/login"), third, new MockFilterChain());
        assertEquals(429, third.getStatus(),
                "a Redis outage must not silently disable login brute-force protection");
    }

    @Test
    void paymentVerifyFailsClosedToLocalWhenRedisIsDown() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("Redis unavailable"));

        RateLimitFilter tight = new RateLimitFilter(redis, false, 20, 2, 60, 30, 60);

        tight.doFilter(request("POST", "/api/payments/order/1/verify"),
                new MockHttpServletResponse(), new MockFilterChain());
        tight.doFilter(request("POST", "/api/payments/order/1/verify"),
                new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse third = new MockHttpServletResponse();
        tight.doFilter(request("POST", "/api/payments/order/99/verify"), third, new MockFilterChain());
        assertEquals(429, third.getStatus(),
                "verify against many order ids must share one quota, including during a Redis outage");
    }

    @Test
    void cartMutationFailsOpenWhenRedisIsDown() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("Redis unavailable"));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("POST", "/api/carts/items"), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "a Redis outage must not freeze the cart");
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRequestURI("/v1" + path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }
}
