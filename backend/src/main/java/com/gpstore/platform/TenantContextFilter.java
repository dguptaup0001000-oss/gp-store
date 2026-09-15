package com.gpstore.platform;

import com.gpstore.security.AdminPermission;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.CurrentUser;
import com.gpstore.security.RolePermissions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts a tenant scope on the thread for the length of one request, and takes
 * it off again.
 *
 * THE FINALLY BLOCK IS THE SECURITY CONTROL. Tomcat hands the same thread to
 * the next request; a scope left behind is the scope that request starts
 * with, which is one shop reading another's data through no fault of any
 * query. That is why the clear is unconditional and why this filter does
 * nothing else - a filter with one job has one place to get it wrong.
 *
 * RUNS AFTER AUTHENTICATION, because the scope comes from the credential and
 * there is no credential until JwtFilter has run. Placed after RateLimitFilter
 * for the same reason the rate limiter is placed after JwtFilter: work that
 * a request will be refused for should be refused before anything reads a
 * database to set up for it.
 *
 * RESOLUTION FAILURE IS NOT A 500. A credential that cannot be resolved to a
 * shop is an authorization problem, and answering 403 says so without leaking
 * whether the shop exists. It also keeps a misconfigured deployment from
 * looking like a broken one.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    /** How a merchant with more than one shop says which one they are working in. */
    public static final String SHOP_HEADER = "X-Shop-Id";

    private final TenantResolver resolver;

    private final ShopOperationGate operationGate;

    private final ShopMembership membership;

    private final CurrentUser currentUser;

    public TenantContextFilter(TenantResolver resolver, ShopOperationGate operationGate,
                               ShopMembership membership, CurrentUser currentUser) {
        this.resolver = resolver;
        this.operationGate = operationGate;
        this.membership = membership;
        this.currentUser = currentUser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (spansEveryShop(request)) {
            // NAMED, NOT ABSENT. These requests legitimately touch rows from
            // any shop - a Cashfree webhook arrives with a signature and no
            // session, and must be able to find the payment it is about
            // whoever sold it. Leaving the scope unset would have the same
            // effect today and say nothing; TenantScope.platform() says it,
            // and makes an unscoped thread anywhere else a bug rather than a
            // maybe.
            TenantContext.runWithin(TenantScope.platform(), () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException failed) {
                    throw new IllegalStateException(failed);
                }
            });
            return;
        }

        try {
            TenantContext.set(resolver.select(requestedShopId(request)));
        } catch (RuntimeException cannotResolve) {
            // No shop id in the message. Whether a given shop exists is not
            // something an unauthenticated caller should be able to learn
            // from an error string.
            log.warn("Refusing a request whose credential resolves to no shop: {}",
                    cannotResolve.getClass().getSimpleName());
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "This account is not associated with a shop.");
            return;
        }

        try {
            if (!mayEnterMerchantBackOffice(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "This account is not associated with this shop.");
                return;
            }
            if (!mayProceed(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "This shop is suspended or closed, so it cannot be changed. You can "
                                + "still see your records, and appeal the decision.");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A staff-shaped credential is not itself a shop grant.
     *
     * SINGLE_SHOP deliberately gives every request a storefront scope so
     * existing customer clients need no shop header. That compatibility
     * fallback must not turn an ACTIVE account carrying a staff role into the
     * merchant of Shop #1. Every merchant self-service route therefore also
     * requires the live shop_staff row that TenantResolver uses for explicit
     * shop selection. Platform administrators are marketplace actors rather
     * than shop staff and retain their separate cross-shop authority.
     *
     * Anonymous and customer requests are left for Spring Security to answer,
     * preserving the established 401/403 contract. This check only closes the
     * gap where a valid staff role would otherwise pass route authorization.
     */
    private boolean mayEnterMerchantBackOffice(HttpServletRequest request) {
        String path = com.gpstore.config.RequestPath.of(request);
        if (!path.startsWith("/api/shop/")) {
            return true;
        }
        if (currentUser.has(AdminPermission.PLATFORM_ADMIN)) {
            return true;
        }
        AuthenticatedUser principal;
        try {
            principal = currentUser.get();
        } catch (IllegalStateException noAuthenticatedPrincipal) {
            return true;
        }
        if (RolePermissions.forRoleName(principal.getRole()).isEmpty()) {
            return true;
        }
        TenantScope scope = TenantContext.current();
        return principal.getCustomerId() != null
                && scope != null
                && scope.isSingleShop()
                && membership.permits(principal.getCustomerId(), scope.shopId());
    }

    /**
     * A shop under enforcement is read-only to its own people.
     *
     * ENFORCED HERE BECAUSE HERE IS THE ONLY CHOKEPOINT. The merchant's back
     * office is spread over /api/shop, /api/admin and more, and a rule applied
     * controller by controller is a rule that is missing from whichever
     * controller is written next. Every scoped request already passes through
     * this filter to get a tenant at all.
     *
     * READS PASS. See ShopOperationGate for why refusing them would take a
     * merchant's own records away from them.
     */
    private boolean mayProceed(HttpServletRequest request) {
        if (isRead(request)) {
            return true;
        }
        TenantScope scope = TenantContext.current();
        if (scope == null || scope.isPlatform()) {
            return true;
        }
        // The platform manages suspended shops - that is what suspension is
        // for - and a rider finishes deliveries that were already paid for.
        if (operationGate.isPlatformActor() || isRiderFinishingWork(request)) {
            return true;
        }
        if (operationGate.mayOperate(scope.shopId())) {
            return true;
        }
        // THE APPEAL IS THE ONE WRITE A SUSPENDED MERCHANT KEEPS. Suspending a
        // business and removing its only way to ask why is a dead end, not
        // enforcement.
        return com.gpstore.config.RequestPath.of(request).startsWith("/api/shop/governance");
    }

    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    /**
     * Orders already placed still have to arrive.
     *
     * A rider's scope comes from their roster row, so without this a
     * suspension against a merchant would strand paid orders mid-delivery and
     * leave customers with neither goods nor a refund. The suspension is
     * against the merchant, not the shopper waiting at the door.
     */
    private static boolean isRiderFinishingWork(HttpServletRequest request) {
        return com.gpstore.config.RequestPath.of(request).startsWith("/api/worker");
    }

    /**
     * Endpoints that exist before, or outside, any shop.
     *
     * Health, version and the actuator are how a load balancer and a deploy
     * script decide whether the application is alive; making them depend on a
     * shop row being present would mean a database problem reads as a dead
     * process. Auth endpoints run before there is a credential to resolve
     * from, and the payment webhook arrives from Cashfree with no session at
     * all - it carries a signature instead, which is verified elsewhere.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = com.gpstore.config.RequestPath.of(request);
        // Liveness only. These must answer while the database is unreachable,
        // so they cannot depend on a shop row existing - a database problem
        // reading as a dead process is how a deploy script kills a healthy
        // instance.
        return path.startsWith("/api/health")
                || path.startsWith("/api/version")
                || path.startsWith("/actuator");
    }

    /**
     * The shop a caller asked to act for, if any.
     *
     * THIS IS THE ONE THING THE FILTER READS FROM THE REQUEST, and it is a
     * SELECTION rather than an identity: TenantResolver.select verifies it
     * against the shops the credential already permits and refuses anything
     * else. A merchant with three kiranas needs a shop switcher; what they
     * must not have is a way to name a fourth.
     *
     * AN UNREADABLE VALUE IS REFUSED, not ignored. Nothing in the shipped apps
     * sends this header, so a value that is present and not a number came from
     * somebody probing - and "ignore what you do not understand" is how a
     * parser difference becomes an authorization bypass.
     */
    private static Long requestedShopId(HttpServletRequest request) {
        String raw = request.getHeader(SHOP_HEADER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalStateException("Unreadable " + SHOP_HEADER + " header.");
        }
    }

    /**
     * Requests that belong to no single shop and must not be scoped to one.
     *
     * Auth runs before there is a credential to resolve a shop from, and the
     * payment webhook arrives from Cashfree with a signature instead of a
     * session. Both are given the platform scope explicitly rather than left
     * unscoped: the reads they do are identical either way, and the write
     * path is not - a row inserted with no scope under a marketplace now
     * fails loudly instead of landing in whichever shop the default named.
     */
    private static boolean spansEveryShop(HttpServletRequest request) {
        String path = com.gpstore.config.RequestPath.of(request);
        return path.startsWith("/api/auth/")
                // A RIDER SIGNS IN THROUGH A DIFFERENT DOOR, and it is still a
                // door. /api/worker/auth/login checks credentials that live on
                // delivery_partners rather than on customers, so it runs before
                // there is anything to resolve a shop from - exactly like
                // /api/auth/ above, and it was missed only because of where it
                // sits in the path tree. Without this, no delivery worker could
                // sign in AT ALL under MULTI_SHOP_PRODUCTION: the filter refused
                // the login request for having no shop, and the shop is on the
                // roster row the login would have found. Under one shop the
                // resolver falls back to Shop #1 and this never showed.
                || path.startsWith("/api/worker/auth/")
                || path.startsWith("/api/payments/webhooks/")
                // FINDING A SHOP CANNOT REQUIRE HAVING ONE. A customer who
                // has just installed the app has no shop yet, and asking them
                // to have one before they may ask which shops exist is a 403
                // on the first screen. These routes read shops and nothing
                // shop-owned - see MarketplaceController.
                || path.startsWith("/api/marketplace/")
                // AND NEITHER CAN SAVING THE ADDRESS THAT FINDS ONE.
                //
                // THE DEADLOCK THIS BREAKS, found by running a real
                // marketplace rather than one shop: a customer's shop is the
                // nearest one that delivers to their ADDRESS, so a customer
                // with no address resolves to no shop - and TenantResolver
                // refuses, correctly, to invent one. That refusal reached
                // every authenticated request they could make, including
                // POST /api/addresses. A new customer on a marketplace could
                // therefore do nothing at all: they could not add the address
                // that would have given them a shop. Under SINGLE_SHOP the
                // resolver falls back to Shop #1 and this was never reachable.
                //
                // AN ADDRESS BELONGS TO A CUSTOMER, NOT TO A SHOP - Address
                // does not implement ShopOwned and every query here is keyed
                // on the customer id from the token - so there is nothing for
                // a shop scope to protect and nothing for the platform scope
                // to widen. Ownership is still checked, on every route, by
                // AddressService.getOwnedAddress.
                //
                // ONE EXCEPTION, KEPT SCOPED: "will you deliver here?" is a
                // question about a SHOP, answered from that shop's radius and
                // its coordinates. It needs a shop and must keep needing one.
                || (path.startsWith("/api/addresses") && !path.endsWith("/deliverable"));
    }
}
