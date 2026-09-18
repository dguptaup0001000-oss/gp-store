package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Reads EVERY endpoint the application publishes, as a merchant who owns one
 * shop, while a second merchant's shop holds recognisable data.
 *
 * <p>WHY A SWEEP AND NOT A LIST. The audit was going controller by controller
 * by hand, and a hand-written list is exactly as complete as the day it was
 * written - the endpoint added next month is not in it. This asks the running
 * application which endpoints exist ({@code RequestMappingHandlerMapping}, the
 * same registry that dispatches requests), so a new route joins the sweep by
 * existing. If somebody publishes a leaking list tomorrow, this test fails
 * without anybody remembering to update it.
 *
 * <p>WHAT IT ASSERTS. One thing, on every readable route: the response does not
 * contain a string that belongs to the OTHER merchant. Merchant B's shop code,
 * shop name, product, customer, that customer's street and their order number
 * are all tagged with a per-run nonce, so a match is unambiguous - no other row
 * in the database can contain it. A 401, 403 or 404 passes; refusing to answer
 * is a fine way to not leak. A 5xx fails, because a crash is not a decision.
 *
 * <p>PATH VARIABLES ARE FILLED WITH MERCHANT B'S REAL IDS. This is the
 * cross-tenant direct-ID attack in bulk: {@code /api/orders/{id}} is asked for
 * B's order id, {@code /api/shop/{shopId}/...} for B's shop. The correct answer
 * is 404 (see GlobalExceptionHandler.handleCrossShop - a 403 would confirm the
 * row exists), and any 200 carrying a B marker is a leak.
 *
 * <p>READS ONLY. The sweep never sends POST, PUT, PATCH or DELETE: this runs
 * against a real database and a writing sweep would be a destructive operation
 * nobody asked for. Write-side tenant isolation is covered by the targeted
 * tests named in each fix's commit.
 */
@SpringBootTest(properties = {
        // No platform.mode: the configuration production boots with.
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("Every endpoint this application publishes, read as another merchant")
class EveryPublishedEndpointSweepTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantLifecycleService merchantLifecycle;
    @Autowired private ShopLifecycleService shopLifecycle;
    // NAMED, because the actuator publishes a second one of these and the
    // sweep must read the registry that dispatches /api requests.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final String tag = "swp" + System.nanoTime();

    private Long merchantA;
    private Long merchantB;
    private long shopA;
    private long shopB;
    private Long ownerA;
    private Long categoryB;
    private Long productB;
    private Long variantB;
    private Long customerB;
    private Long addressB;
    private Long orderB;
    private Long cartB;
    private Long groupB;
    private Long invoiceB;
    private Long paymentB;
    private Long cartItemB;
    private String emailB;
    private String mobileB;

    /**
     * Merchant B's rows that no stranger may ever see, whoever asks.
     *
     * <p>A customer's name, the street they live on, and the number of an
     * order they placed. None of these is public information on any route.
     */
    private final Map<String, String> neverPublic = new LinkedHashMap<>();

    /**
     * Merchant B's rows that ARE public on the marketplace's browse routes.
     *
     * <p>A shop's name and code, the products it lists and the department they
     * sit in are what a shopper reads before choosing a shop - GP-STORE would
     * not be a marketplace if a stranger could not see them. So their presence
     * is judged against what an anonymous caller gets from the SAME route: if
     * the route is public, a merchant reading it has learned nothing that the
     * whole internet does not already have. If the route answers the merchant
     * and refuses the stranger, then it is a back-office route showing one shop
     * another shop's catalogue, and that is a leak.
     *
     * <p>This is deliberately not a list of excused paths. The excuse has to be
     * earned per request, by the route actually being public when asked.
     */
    private final Map<String, String> publicOnBrowseRoutes = new LinkedHashMap<>();

    @BeforeEach
    void twoMerchants() {
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        merchantA = newMerchant("a");
        merchantB = newMerchant("b");
        shopA = newShop(merchantA, "SWA-" + tag);
        shopB = newShop(merchantB, "SWB-" + tag);
        ownerA = newStaffFor(shopA, "a");

        categoryB = newCategory("Sweep Cat B " + tag);
        productB = newListedProduct(shopB, categoryB, "Sweep Product B " + tag);
        variantB = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productB);
        jdbc.update("UPDATE product_variants SET cost_price = 37.37 WHERE id = ?", variantB);

        emailB = tag + "-buyerb@example.test";
        customerB = newCustomer("Sweep Buyer B " + tag, emailB);
        mobileB = jdbc.queryForObject(
                "SELECT mobile_number FROM customers WHERE id = ?", String.class, customerB);
        jdbc.update("INSERT INTO addresses (customer_id, house_no, area, city, "
                        + "latitude, longitude, default_address) "
                        + "VALUES (?, ?, 'Sweepville', 'Bengaluru', 12.97, 77.59, true)",
                customerB, "Sweep Lane " + tag);
        addressB = jdbc.queryForObject(
                "SELECT id FROM addresses WHERE customer_id = ?", Long.class, customerB);
        orderB = newOrder(shopB, customerB, "SWB-ORD-" + tag);

        // A BASKET, AN INVOICE, A PAYMENT AND A MULTI-SHOP ORDER GROUP, because
        // the routes that take these ids by name are exactly the ones a
        // path-variable sweep skips unless the rows exist to point it at.
        jdbc.update("INSERT INTO carts (customer_id, total_amount, total_items) "
                + "VALUES (?, 100, 1)", customerB);
        cartB = jdbc.queryForObject(
                "SELECT id FROM carts WHERE customer_id = ?", Long.class, customerB);
        jdbc.update("INSERT INTO cart_items (cart_id, product_variant_id, shop_id, "
                + "quantity, price, total_price) VALUES (?, ?, ?, 1, 100, 100)",
                cartB, variantB, shopB);
        cartItemB = jdbc.queryForObject(
                "SELECT id FROM cart_items WHERE cart_id = ?", Long.class, cartB);
        jdbc.update("INSERT INTO invoices (invoice_number, shop_id, customer_id, order_id, "
                        + "subtotal, grand_total, invoice_date, status, active) "
                        + "VALUES (?, ?, ?, ?, 100, 100, now(), 'GENERATED', true)",
                "SWB-INV-" + tag, shopB, customerB, orderB);
        invoiceB = jdbc.queryForObject(
                "SELECT id FROM invoices WHERE invoice_number = ?", Long.class,
                "SWB-INV-" + tag);
        jdbc.update("INSERT INTO payments (order_id, shop_id, transaction_id, amount, "
                        + "payment_method, payment_status, payment_date, active) "
                        + "VALUES (?, ?, ?, 100, 'COD', 'SUCCESS', now(), true)",
                orderB, shopB, "SWB-TXN-" + tag);
        paymentB = jdbc.queryForObject(
                "SELECT id FROM payments WHERE transaction_id = ?", Long.class,
                "SWB-TXN-" + tag);
        jdbc.update("INSERT INTO order_groups (group_number, customer_id, address_id, "
                        + "shop_count, total_amount, created_at) "
                        + "VALUES (?, ?, ?, 1, 100, now())",
                "SWB-GRP-" + tag, customerB, addressB);
        groupB = jdbc.queryForObject(
                "SELECT id FROM order_groups WHERE group_number = ?", Long.class,
                "SWB-GRP-" + tag);

        neverPublic.put("customer", "Sweep Buyer B " + tag);
        neverPublic.put("customer's street", "Sweep Lane " + tag);
        neverPublic.put("order number", "SWB-ORD-" + tag);
        neverPublic.put("merchant's registered name", "Sweep Merchant b " + tag);
        neverPublic.put("customer's email", emailB);
        neverPublic.put("customer's mobile number", mobileB);
        neverPublic.put("invoice number", "SWB-INV-" + tag);
        neverPublic.put("payment transaction id", "SWB-TXN-" + tag);
        neverPublic.put("order group number", "SWB-GRP-" + tag);

        publicOnBrowseRoutes.put("shop code", "SWB-" + tag);
        publicOnBrowseRoutes.put("product", "Sweep Product B " + tag);
        // NO CATEGORY MARKER, and the reason is not convenience. Categories are
        // the PLATFORM'S taxonomy, not a merchant's row: creating one takes
        // CATALOG_DEFINE, which no shop role holds on a marketplace, and
        // GET /api/categories deliberately answers the whole list to everybody
        // because that is what the Add Product screen offers a merchant to
        // choose from. The merchant-facing question - which departments does
        // THIS shop trade in - is /api/categories/mine, and has its own test
        // (AMerchantAddsWhatTheySellTest). Treating a shared taxonomy row as
        // one shop's property would also be exactly the "category as a security
        // boundary" mistake this work was told not to make.
    }

    @AfterEach
    void tidyUp() {
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ?", cartB);
        jdbc.update("DELETE FROM carts WHERE id = ?", cartB);
        jdbc.update("DELETE FROM invoices WHERE shop_id IN (?, ?)", shopA, shopB);
        jdbc.update("DELETE FROM payments WHERE shop_id IN (?, ?)", shopA, shopB);
        jdbc.update("DELETE FROM order_groups WHERE id = ?", groupB);
        jdbc.update("DELETE FROM order_items WHERE order_id IN "
                + "(SELECT id FROM orders WHERE shop_id IN (?, ?))", shopA, shopB);
        jdbc.update("DELETE FROM orders WHERE shop_id IN (?, ?)", shopA, shopB);
        jdbc.update("DELETE FROM addresses WHERE customer_id = ?", customerB);
        for (long shop : new long[]{shopA, shopB}) {
            jdbc.update("DELETE FROM inventory WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shop);
            jdbc.update("DELETE FROM shops WHERE id = ?", shop);
        }
        jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productB);
        jdbc.update("DELETE FROM products WHERE id = ?", productB);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryB);
        jdbc.update("DELETE FROM merchants WHERE id in (?, ?)", merchantA, merchantB);
        jdbc.update("DELETE FROM customers WHERE id in (?, ?)", ownerA, customerB);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("no readable route hands one merchant another merchant's rows")
    void sweepEveryReadableRoute() throws Exception {
        List<String> leaks = new ArrayList<>();
        List<String> crashes = new ArrayList<>();
        Set<String> swept = new TreeSet<>();
        Set<String> skipped = new TreeSet<>();

        for (String path : readablePaths()) {
            List<String> fillings = fillWithMerchantBsIds(path);
            if (fillings.isEmpty()) {
                skipped.add(path);
                continue;
            }
            for (String filled : fillings) {
            String url = withPaging(filled);

            String asMerchant;
            int status;
            try {
                MvcResult result = mockMvc.perform(
                        get(url).with(authentication(tokenFor(ownerA)))).andReturn();
                status = result.getResponse().getStatus();
                asMerchant = result.getResponse().getContentAsString();
            } catch (Exception dispatchFailed) {
                crashes.add("GET " + filled + " threw " + dispatchFailed.getClass().getSimpleName()
                        + ": " + String.valueOf(dispatchFailed.getMessage()));
                continue;
            }
            swept.add(status + " " + filled);

            for (Map.Entry<String, String> marker : neverPublic.entrySet()) {
                if (asMerchant.contains(marker.getValue())) {
                    leaks.add("GET " + filled + " answered " + status
                            + " carrying another merchant's " + marker.getKey()
                            + " (" + marker.getValue() + "), which is not public on any "
                            + "route. Body: " + excerpt(asMerchant));
                }
            }

            for (Map.Entry<String, String> marker : publicOnBrowseRoutes.entrySet()) {
                if (!asMerchant.contains(marker.getValue())) {
                    continue;
                }
                // Earned per request: is this route public, or is the merchant
                // being shown something a stranger is refused?
                MvcResult strangerResult = mockMvc.perform(get(url)).andReturn();
                int strangerStatus = strangerResult.getResponse().getStatus();
                String asStranger = strangerResult.getResponse().getContentAsString();
                if (!asStranger.contains(marker.getValue())) {
                    System.out.println("[SWEEP][public?] " + filled + " stranger -> "
                            + strangerStatus + " " + excerpt(asStranger));
                    leaks.add("GET " + filled + " answered the merchant " + status
                            + " carrying another shop's " + marker.getKey()
                            + " (" + marker.getValue() + ") while refusing an anonymous "
                            + "caller the same row - so this is a back-office route showing "
                            + "one shop another shop's catalogue. Body: "
                            + excerpt(asMerchant));
                }
            }

            if (status >= 500) {
                crashes.add("GET " + filled + " -> " + status + " " + excerpt(asMerchant));
            }
            }
        }

        System.out.println("[SWEEP] read " + swept.size()
                + " routes as a merchant of another shop");
        swept.forEach(line -> System.out.println("[SWEEP] " + line));
        // THE GAPS ARE PART OF THE RESULT. A sweep that quietly skips routes
        // reports a completeness it has not got.
        System.out.println("[SWEEP] skipped " + skipped.size()
                + " route(s) whose path variable this sweep cannot honestly fill:");
        skipped.forEach(line -> System.out.println("[SWEEP][skipped] " + line));

        assertTrue(leaks.isEmpty(), leaks.size() + " route(s) leaked across the tenant "
                + "boundary:\n - " + String.join("\n - ", leaks));
        // A 500 is a different defect - the route did not decide, it fell over -
        // and stays a hard failure so a crash cannot hide a leak behind a stack
        // trace.
        assertTrue(crashes.isEmpty(), crashes.size() + " route(s) crashed rather than "
                + "answering:\n - " + String.join("\n - ", crashes));
    }

    private static String excerpt(String body) {
        return body.substring(0, Math.min(body.length(), 600));
    }

    // ------------------------------------------------------------------

    /** Every GET the running application publishes, from its own dispatch registry. */
    private Set<String> readablePaths() {
        Set<String> paths = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            RequestMethodsRequestCondition methods = info.getMethodsCondition();
            boolean readable = methods.getMethods().isEmpty()
                    || methods.getMethods().stream()
                            .anyMatch(m -> m.name().equals("GET"));
            if (!readable) {
                continue;
            }
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(paths::add);
            } else if (info.getPatternsCondition() != null) {
                paths.addAll(info.getPatternsCondition().getPatterns());
            }
        }
        paths.removeIf(p -> !p.startsWith("/api"));
        return paths;
    }

    /**
     * Substitutes merchant B's real identifiers into the path.
     *
     * <p>THE POINT OF THE SWEEP IS THE SUBSTITUTION. A route with an id in it
     * only leaks when somebody asks it for a row that is not theirs, so every
     * {@code {id}} gets B's id for whatever the route is about. Where the sweep
     * cannot tell what an id means it returns null and skips the route rather
     * than sending a number that proves nothing - and every skip is reported in
     * the run log, so the gaps are visible rather than silent.
     */
    private List<String> fillWithMerchantBsIds(String path) {
        List<String> urls = new ArrayList<>();
        for (String bareId : bareIdCandidatesFor(path)) {
            String filled = path;
            boolean complete = true;
            while (filled.contains("{")) {
                int open = filled.indexOf('{');
                int close = filled.indexOf('}', open);
                if (close < 0) {
                    complete = false;
                    break;
                }
                String name = filled.substring(open + 1, close).toLowerCase();
                String value = name.equals("id") ? bareId : valueFor(name);
                if (value == null) {
                    complete = false;
                    break;
                }
                filled = filled.substring(0, open) + value + filled.substring(close + 1);
            }
            if (complete && !urls.contains(filled)) {
                urls.add(filled);
            }
        }
        return urls;
    }

    /**
     * What a bare {@code {id}} means, and the mistake this exists to stop.
     *
     * <p>THE FIRST VERSION PUT B's ORDER ID IN EVERY {@code {id}}, and reported
     * {@code /api/customers/{id}/detail} clean - because an order id is not a
     * customer id, so the route answered "no such customer" and the sweep
     * called that a refusal. A probe that passes by asking the wrong question
     * is worse than no probe, and this is the second time that exact mistake
     * has been made in this audit.
     *
     * <p>So a route whose prefix says what its id is gets that id, and a route
     * whose prefix says nothing is asked with EVERY id merchant B owns - order,
     * customer, shop, product, variant, cart, cart line, address, invoice,
     * payment, order group, merchant, category. Twelve requests is cheap; a
     * wrong single guess is a false clean.
     */
    private List<String> bareIdCandidatesFor(String path) {
        if (!path.contains("{id}")) {
            return List.of("");
        }
        Map<String, Long> byPrefix = new LinkedHashMap<>();
        byPrefix.put("/api/customers/", customerB);
        byPrefix.put("/api/orders/", orderB);
        byPrefix.put("/api/products/", productB);
        byPrefix.put("/api/product-variants/", variantB);
        byPrefix.put("/api/categories/", categoryB);
        byPrefix.put("/api/addresses/", addressB);
        byPrefix.put("/api/carts/", cartB);
        byPrefix.put("/api/cart-items/", cartItemB);
        byPrefix.put("/api/invoices/", invoiceB);
        byPrefix.put("/api/payments/", paymentB);
        byPrefix.put("/api/marketplace/shops/", shopB);
        for (Map.Entry<String, Long> prefix : byPrefix.entrySet()) {
            if (path.startsWith(prefix.getKey())) {
                return List.of(String.valueOf(prefix.getValue()));
            }
        }
        List<String> every = new ArrayList<>();
        for (Long id : new Long[]{orderB, customerB, shopB, productB, variantB, cartB,
                cartItemB, addressB, invoiceB, paymentB, groupB, merchantB, categoryB}) {
            String value = String.valueOf(id);
            if (!every.contains(value)) {
                every.add(value);
            }
        }
        return every;
    }

    private String valueFor(String name) {
        if (name.startsWith("shop")) {
            return String.valueOf(shopB);
        }
        if (name.startsWith("merchant")) {
            return String.valueOf(merchantB);
        }
        if (name.startsWith("order") && !name.startsWith("ordergroup")) {
            return String.valueOf(orderB);
        }
        if (name.startsWith("product") && !name.contains("variant")) {
            return String.valueOf(productB);
        }
        if (name.contains("variant")) {
            return String.valueOf(variantB);
        }
        if (name.startsWith("categor")) {
            return String.valueOf(categoryB);
        }
        if (name.startsWith("customer") || name.startsWith("user")) {
            return String.valueOf(customerB);
        }
        if (name.startsWith("address")) {
            return String.valueOf(addressB);
        }
        if (name.startsWith("cart")) {
            return String.valueOf(cartB);
        }
        if (name.startsWith("group")) {
            return String.valueOf(groupB);
        }
        if (name.equals("email")) {
            return emailB;
        }
        if (name.startsWith("mobile") || name.startsWith("phone")) {
            return mobileB;
        }
        if (name.startsWith("invoicenumber")) {
            return "SWB-INV-" + tag;
        }
        if (name.startsWith("transactionid")) {
            return "SWB-TXN-" + tag;
        }
        if (name.startsWith("brand")) {
            return "Sweep";
        }
        if (name.startsWith("entitytype")) {
            return "ORDER";
        }
        if (name.startsWith("entityid")) {
            return String.valueOf(orderB);
        }
        if (name.startsWith("status")) {
            return "PENDING";
        }
        // A path variable constrained by a regex ({resource:a|b|c}) - take the
        // first alternative the route itself declares, so the sweep asks for a
        // resource that exists rather than inventing one.
        if (name.contains(":")) {
            return name.substring(name.indexOf(':') + 1).split("\\|")[0];
        }
        // A bare {id} means whatever the controller is about, which the path
        // alone does not say. B's order id is the most widely meaningful
        // choice: it is a real row of B's, so a route that answers it at all
        // has crossed the boundary.
        if (name.equals("id")) {
            return String.valueOf(orderB);
        }
        return null;
    }

    /** Asks for a big page, so a leak cannot hide on page two. */
    private String withPaging(String path) {
        return path + (path.contains("?") ? "&" : "?") + "page=0&size=200&limit=200";
    }

    // ------------------------------------------------------------------

    private Long newMerchant(String kind) {
        Long id = merchantLifecycle.register(
                "Sweep Merchant " + kind + " " + tag, "Sweep Shop",
                null, null, null, true).getId();
        merchantLifecycle.transition(id, MerchantStatus.PENDING_REVIEW, "submitted");
        merchantLifecycle.transition(id, MerchantStatus.APPROVED, "checked");
        merchantLifecycle.transition(id, MerchantStatus.ACTIVE, "trading");
        return id;
    }

    private long newShop(Long merchant, String code) {
        long id = shopLifecycle.open(merchant, code, "Sweep Shop " + code,
                12.9716, 77.5946, new java.math.BigDecimal("5"), "Asia/Kolkata").getId();
        shopLifecycle.transitionAsPlatform(id, ShopStatus.ACTIVE, "ready to trade");
        return id;
    }

    private Long newStaffFor(long shop, String kind) {
        Long id = newCustomer("Sweep Owner " + kind + " " + tag,
                tag + "-" + kind + "@example.test");
        jdbc.update("INSERT INTO shop_staff (shop_id, customer_id, is_default, active) "
                + "VALUES (?, ?, true, true)", shop, id);
        return id;
    }

    private Long newCustomer(String name, String email) {
        // A shopper is a CUSTOMER; only the owner account below is staff.
        String role = name.contains("Owner") ? "ADMIN" : "CUSTOMER";
        jdbc.update("INSERT INTO customers (full_name, email, mobile_number, password, "
                        + "role, active) VALUES (?, ?, ?, 'not-a-real-hash', ?, true)",
                name, email, "9" + (100000000 + (int) (Math.random() * 899999999)), role);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private Long newCategory(String name) {
        jdbc.update("INSERT INTO categories (name, description, active) VALUES (?, ?, true)",
                name, name + " department");
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    private Long newListedProduct(long shop, Long category, String name) {
        jdbc.update("INSERT INTO products (name, brand, category_id, active) "
                + "VALUES (?, 'Sweep', ?, true)", name, category);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, unit, selling_price, mrp, available, active) "
                + "VALUES (?, 'one', 100, 120, true, true)", productId);
        Long v = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ?", Long.class, productId);
        jdbc.update("INSERT INTO shop_product_variants "
                + "(shop_id, product_variant_id, selling_price, mrp, available, active) "
                + "VALUES (?, ?, 100, 120, true, true)", shop, v);
        return productId;
    }

    private Long newOrder(long shop, Long customerId, String orderNumber) {
        jdbc.update("INSERT INTO orders (shop_id, customer_id, order_number, order_status, "
                        + "total_amount, order_date) VALUES (?, ?, ?, 'DELIVERED', 100, now())",
                shop, customerId, orderNumber);
        return jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", Long.class, orderNumber);
    }

    private UsernamePasswordAuthenticationToken tokenFor(Long accountId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(Role.ADMIN)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", Role.ADMIN.name()),
                null, authorities);
    }
}
