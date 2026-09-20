package com.gpstore.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The directory must cost the same for a big merchant as for a small one.
 *
 * <h2>Why this test counts instead of timing</h2>
 *
 * <p>An N+1 is invisible in a small database: a merchant with two shops
 * responds fast whether the code asks once or twice per shop. It only becomes
 * a problem in production, against real row counts and a real network, by
 * which point it is somebody's outage. A COUNT makes it detectable here.
 *
 * <p>The marketplace work removed exactly this shape - a per-shop loop in
 * discovery - and the brief for this feature said in as many words not to
 * reintroduce it. So this is the regression test for that promise.
 *
 * <h2>Why it spies on the JdbcTemplate</h2>
 *
 * <p>The existing {@code QueryCounter} reads Hibernate's statement statistics,
 * and the control tower deliberately does not use Hibernate - it is raw JDBC
 * so it can span shops without the tenant filter narrowing it. Hibernate
 * therefore sees none of these statements and would report a comforting zero.
 * Counting calls on the template the service actually uses is the measurement
 * that corresponds to reality.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("The directory stays bounded")
class SuperAdminDirectoryStaysBoundedTest {

    @MockitoSpyBean private NamedParameterJdbcTemplate spiedJdbc;
    @Autowired private PlatformControlTowerService service;
    @Autowired private JdbcTemplate jdbc;

    private final String tag = "bound" + System.nanoTime();
    private Long buyer;
    private Long smallMerchant;
    private Long bigMerchant;
    private final List<Long> shopIds = new ArrayList<>();
    private final List<String> orderNumbers = new ArrayList<>();

    @BeforeEach
    void oneSmallMerchantAndOneMuchLargerOne() {
        buyer = customer("Bounded Buyer " + tag, tag + "-buyer@example.test", "9500000001");

        smallMerchant = merchant("Small " + tag);
        shop(smallMerchant, "small-1-" + tag);

        bigMerchant = merchant("Big " + tag);
        // Six shops and twenty-four orders. If anything asks per shop or per
        // order, the count below moves and this test says so.
        for (int i = 0; i < 6; i++) {
            Long shopId = shop(bigMerchant, "big-" + i + "-" + tag);
            for (int j = 0; j < 4; j++) {
                order(shopId, buyer, "GPS-" + tag + "-" + i + "-" + j);
            }
        }
    }

    @AfterEach
    void tidyUp() {
        if (!orderNumbers.isEmpty()) {
            jdbc.update("DELETE FROM orders WHERE order_number IN ("
                    + "?" + ",?".repeat(orderNumbers.size() - 1) + ")",
                    orderNumbers.toArray());
        }
        for (Long shopId : shopIds) {
            jdbc.update("DELETE FROM shops WHERE id=?", shopId);
        }
        jdbc.update("DELETE FROM merchants WHERE id IN (?,?)", smallMerchant, bigMerchant);
        jdbc.update("DELETE FROM audit_logs WHERE entity_type='Customer' AND entity_id=?", buyer);
        jdbc.update("DELETE FROM customers WHERE id=?", buyer);
    }

    @Test
    @DisplayName("a merchant profile costs the same at one shop and at six")
    void theMerchantProfileDoesNotScaleWithShops() {
        LocalDateTime to = LocalDateTime.now().plusDays(1);
        LocalDateTime from = to.minusDays(60);

        int small = templateCallsFor(() -> service.merchantProfile(smallMerchant, from, to));
        int big = templateCallsFor(() -> service.merchantProfile(bigMerchant, from, to));

        assertEquals(small, big,
                "the profile issued " + small + " statements for a merchant with one shop and "
                        + big + " for one with six and twenty-four orders. A difference is a "
                        + "loop over shops or orders, which is the N+1 the marketplace work "
                        + "removed and this feature promised not to reintroduce");

        // A ceiling as well as a comparison: equal-but-enormous would pass
        // the assertion above while still being wrong. In template calls (see
        // the note on the helper), the profile measures in the high thirties,
        // which is roughly a dozen grouped statements.
        assertTrue(big <= 60, "a merchant profile should be a dozen grouped statements; "
                + "this took " + big + " template calls");
    }

    @Test
    @DisplayName("a customer profile costs the same at one order and at twenty-four")
    void theCustomerProfileDoesNotScaleWithOrders() {
        Long quiet = customer("Quiet " + tag, tag + "-quiet@example.test", "9500000002");
        try {
            int busy = templateCallsFor(() -> service.customerProfile(buyer));
            int silent = templateCallsFor(() -> service.customerProfile(quiet));

            assertEquals(silent, busy,
                    "a customer with twenty-four orders across six shops cost " + busy
                            + " statements and one with none cost " + silent
                            + ". The where-they-buy breakdown must be a GROUP BY, never a "
                            + "fetch-every-order-and-count-in-Java");
            assertTrue(busy <= 60, "a customer profile should be a dozen grouped statements; "
                    + "this took " + busy + " template calls");
        } finally {
            jdbc.update("DELETE FROM customers WHERE id=?", quiet);
        }
    }

    @Test
    @DisplayName("a search costs two statements however many rows match")
    void searchIsAPageAndACount() {
        int narrow = templateCallsFor(() -> service.searchMerchants("Small " + tag, 0, 20));
        int wide = templateCallsFor(() -> service.searchMerchants(tag, 0, 20));

        assertEquals(narrow, wide,
                "one match cost " + narrow + " statements and seven cost " + wide
                        + " - a per-result query is the shape that makes a search box "
                        + "unusable on a real marketplace");
        assertTrue(wide <= 8,
                "a page and a count is two statements; this took " + wide + " template calls");
    }

    @Test
    @DisplayName("the customer search is bounded the same way")
    void customerSearchIsAlsoTwo() {
        int count = templateCallsFor(() -> service.searchCustomers(tag, 0, 20));
        assertTrue(count <= 8,
                "a page and a count is two statements; this took " + count + " template calls");
    }

    // ------------------------------------------------------------- fixture

    /**
     * How many statements the block issued through the template the control
     * tower actually uses. Reset immediately before, so nothing a previous
     * test left behind is attributed here.
     */
    private int templateCallsFor(Runnable work) {
        Mockito.clearInvocations(spiedJdbc);
        work.run();
        // ONLY THE METHODS THAT ISSUE SQL - not accessors like
        // getJdbcOperations(), which would make the figure meaningless.
        //
        // THE UNIT IS TEMPLATE CALLS, NOT ROUND TRIPS, and the difference is
        // worth stating: NamedParameterJdbcTemplate delegates between its own
        // public overloads, so one logical statement is recorded two or three
        // times. That inflation is CONSTANT per statement, which is why the
        // comparisons below are exact and meaningful - an extra query per
        // shop moves the number by the same factor - while the ceilings are
        // only sanity bounds and are deliberately expressed in this unit
        // rather than pretending to be a round-trip count.
        return (int) Mockito.mockingDetails(spiedJdbc).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(name -> name.startsWith("query") || name.startsWith("update")
                        || name.startsWith("execute") || name.startsWith("batchUpdate"))
                .count();
    }

    private Long customer(String name, String email, String mobile) {
        jdbc.update("""
                INSERT INTO customers
                    (full_name,email,mobile_number,password,role,enabled,active,verified,created_at)
                VALUES (?,?,?,'not-a-real-hash','CUSTOMER',true,true,true,CURRENT_TIMESTAMP)
                """, name, email, mobile);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email=?", Long.class, email);
    }

    private Long merchant(String name) {
        jdbc.update("""
                INSERT INTO merchants
                    (legal_name,display_name,contact_email,contact_phone,contact_name,
                     status,active,is_demo,created_at,updated_at)
                VALUES (?,?,?,'9500000000',?,'ACTIVE',true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, name, name, name.toLowerCase().replace(' ', '-') + "@example.test", name);
        return jdbc.queryForObject("SELECT id FROM merchants WHERE legal_name=?", Long.class, name);
    }

    private Long shop(Long merchantId, String code) {
        jdbc.update("""
                INSERT INTO shops
                    (merchant_id,code,display_name,status,active,is_demo,
                     latitude,longitude,max_delivery_radius_km,time_zone)
                VALUES (?,?,?,'ACTIVE',true,true,21.1,79.1,10,'Asia/Kolkata')
                """, merchantId, code, code);
        Long id = jdbc.queryForObject("SELECT id FROM shops WHERE code=?", Long.class, code);
        shopIds.add(id);
        return id;
    }

    private void order(Long shopId, Long customerId, String number) {
        jdbc.update("""
                INSERT INTO orders
                    (order_number,shop_id,customer_id,total_amount,delivery_fee,
                     order_status,order_date)
                VALUES (?,?,?,?,20,'DELIVERED',CURRENT_TIMESTAMP)
                """, number, shopId, customerId, new BigDecimal("120"));
        orderNumbers.add(number);
    }
}
