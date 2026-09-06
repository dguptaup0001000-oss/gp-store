package com.gpstore.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The path a filter should match on, the same way in every container.
 *
 * WHY THIS EXISTS. Five filters decided what to do by calling
 * {@code request.getServletPath()}. Under a Spring Boot deployment the
 * DispatcherServlet is mapped at "/", so that returns the whole path and every
 * one of those checks works. Under MockMvc it returns the EMPTY STRING, and
 * the path is only on the request URI.
 *
 * So every one of those checks silently matched nothing in the entire test
 * suite. Not one of them failed, because the fallbacks were all benign under
 * one shop: TenantContextFilter's marketplace branch was skipped and the
 * resolver answered Shop #1 anyway, so the platform-scope path it exists to
 * take had never once been executed by a test. It surfaced the first time a
 * test ran the marketplace in MULTI_SHOP_PRODUCTION, where the fallback is a
 * 403 on the app's first screen instead.
 *
 * A TEST THAT PASSES FOR THE WRONG REASON IS WORSE THAN NO TEST, because it
 * is counted. The point of this class is not that the production behaviour was
 * broken - it was not - but that nothing could see it if it broke.
 *
 * The request URI minus the context path is the same string in both worlds,
 * and it is what Spring's own request matchers use.
 */
public final class RequestPath {

    private RequestPath() {
    }

    /**
     * The application-relative path: no scheme, no host, no context path, no
     * query string.
     *
     * Falls back to the servlet path when the URI is somehow absent, so a
     * container that behaves differently again cannot make this return null
     * and turn every startsWith into a NullPointerException inside a filter.
     */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            String servletPath = request.getServletPath();
            return servletPath == null ? "" : servletPath;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }
}
