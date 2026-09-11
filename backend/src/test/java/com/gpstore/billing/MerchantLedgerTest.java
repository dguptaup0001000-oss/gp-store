package com.gpstore.billing;

import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE MERCHANT LEDGER, AGAINST A REAL DATABASE (§9).
 *
 * <p>§9 asks for ledger history rather than mutable totals, and the reason is
 * practical rather than architectural: when a merchant disputes a charge four
 * months later, "you owe 812" is unanswerable. A list of rows with dates,
 * reasons and the order each came from is an answer.
 *
 * <p>SO THE CENTRAL TEST HERE IS THAT THE DATABASE REFUSES TO REWRITE HISTORY.
 * A ledger that can be updated is a ledger whose past is a matter of trust,
 * and the whole point of having one is to replace trust with a record. A
 * trigger enforces it, not a code review, and this is what proves the trigger
 * is really there.
 *
 * <p>AND THAT NOBODY IS BILLED YET. Part 4 §5 says the commercial amounts are
 * not decided; every merchant on this platform has no tier, and a merchant
 * with no tier closes their week at zero rather than at a number this code
 * invented.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("The merchant ledger")
class MerchantLedgerTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantRepository merchants;
    @Autowired private ShopRepository shops;
    @Autowired private PlatformProperties platform;
    @Autowired private WeeklyBilling billing;
    @Autowired private BillingPlanRepository plans;
    @Autowired private MerchantLedgerRepository ledger;

    private final String tag = "bill" + System.nanoTime();
    /** A Monday, so the week is unambiguous. */
    private final LocalDate week = LocalDate.of(2026, 3, 2);

    private Long merchantId;
    private Long shopId;
    private Long planId;

    @BeforeEach
    void aMerchantWithAShop() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant merchant = new Merchant();
        merchant.setLegalName("Billing fixture " + tag);
        merchant.setDisplayName("Billing fixture");
        merchant.setStatus(MerchantStatus.ACTIVE);
        merchant.setIsDemo(Boolean.TRUE);
        merchant.setActive(Boolean.TRUE);
        merchantId = merchants.save(merchant).getId();

        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode("BILL-" + tag);
        shop.setDisplayName("Billing fixture shop");
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        shopId = shops.save(shop).getId();
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        // The ledger refuses DELETE through JPA's trigger too, so the fixture
        // takes the trigger off for its own rows and puts it back - which is
        // itself a small proof that nothing in the APPLICATION can do this.
        jdbc.execute("ALTER TABLE merchant_ledger_entry DISABLE TRIGGER trg_merchant_ledger_append_only");
        jdbc.update("DELETE FROM merchant_ledger_entry WHERE merchant_id = ?", merchantId);
        jdbc.execute("ALTER TABLE merchant_ledger_entry ENABLE TRIGGER trg_merchant_ledger_append_only");
        jdbc.update("DELETE FROM billing_period WHERE merchant_id = ?", merchantId);
        if (planId != null) {
            jdbc.update("DELETE FROM billing_plan WHERE id = ?", planId);
        }
        jdbc.update("DELETE FROM orders WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // ----------------------------------------------------- nobody is billed

    @Test
    @DisplayName("a merchant with no tier is not billed at all")
    void noTierNoBill() {
        WeeklyBilling.WeekClosed closed = billing.closeWeek(merchantId, week, "billing-test");

        assertFalse(closed.billed(),
                "PART 4 §5 SAYS THE AMOUNTS ARE NOT DECIDED. Every merchant on this platform "
                        + "has no tier today, and a merchant nobody has priced must close their "
                        + "week at zero rather than at a number this code invented.");
        assertEquals("MERCHANT_HAS_NO_TIER", closed.note());
        assertEquals(0, billing.balanceOf(merchantId).signum());
    }

    @Test
    @DisplayName("a tier with no plan in force is not billed either")
    void noPlanNoBill() {
        setTier(MerchantTier.SMALL);
        WeeklyBilling.WeekClosed closed = billing.closeWeek(merchantId, week, "billing-test");

        assertFalse(closed.billed());
        assertEquals("NO_PLAN_IN_FORCE", closed.note(),
                "a tier is a band, not a price - the price is a plan somebody configured");
    }

    // ------------------------------------------------------- §6's arithmetic

    @Test
    @DisplayName("a quiet week costs the fee, and the ledger shows why")
    void feeActsAsAFloor() {
        setTier(MerchantTier.SMALL);
        configurePlan("200.00", 250);
        deliveredOrder("1000.00", "40.00");

        WeeklyBilling.WeekClosed closed = billing.closeWeek(merchantId, week, "billing-test");

        // 2.5% of (1000 - 40 delivery) = 24.00, which is under the 200 fee.
        assertEquals(new BigDecimal("200.00"), closed.platformFee());
        assertEquals(new BigDecimal("24.00"), closed.commission(),
                "§8: NO COMMISSION ON THE DELIVERY CHARGE. 2.5% of 960, not of 1000.");
        assertEquals(new BigDecimal("24.00"), closed.commissionCredit());
        assertEquals(new BigDecimal("200.00"), closed.payable(), "§6: max(P, C) = P");

        // AND THE ROWS EXPLAIN IT, which is the point of a ledger. Not "you
        // owe 200" but a fee, a commission, and a credit that says why the two
        // did not simply add up.
        List<MerchantLedgerEntry> rows = ledger.findByBillingPeriodIdOrderByIdAsc(closed.periodId());
        assertEquals(3, rows.size(), rows.toString());
        assertEquals(LedgerEntryType.PLATFORM_FEE, rows.get(0).getEntryType());
        assertEquals(LedgerEntryType.COMMISSION, rows.get(1).getEntryType());
        assertEquals(LedgerEntryType.COMMISSION_CREDIT, rows.get(2).getEntryType());
        assertEquals(-1, rows.get(2).getAmount().signum(),
                "a credit is negative: positive means the merchant owes GP-STORE");
    }

    @Test
    @DisplayName("a busy week costs the commission, and the fee is absorbed rather than added")
    void commissionOvertakesTheFee() {
        setTier(MerchantTier.SMALL);
        configurePlan("200.00", 250);
        deliveredOrder("40000.00", "0.00");

        WeeklyBilling.WeekClosed closed = billing.closeWeek(merchantId, week, "billing-test");

        assertEquals(new BigDecimal("1000.00"), closed.commission());
        assertEquals(new BigDecimal("1000.00"), closed.payable(),
                "§6: max(P, C) = C. THE FEE IS A CREDIT, NOT A COVER CHARGE - adding it on top "
                        + "would bill the merchant 1200 for a week §6 prices at 1000.");
    }

    // ------------------------------------------------------------------- §7

    @Test
    @DisplayName("a week with no orders gets the fee back")
    void noOrdersNoFee() {
        setTier(MerchantTier.SMALL);
        configurePlan("200.00", 250);

        WeeklyBilling.WeekClosed closed = billing.closeWeek(merchantId, week, "billing-test");

        assertEquals(new BigDecimal("200.00"), closed.feeRefunded());
        assertEquals(0, closed.payable().signum(),
                "§7: 100% of the weekly fee comes back when an eligible merchant completed "
                        + "nothing - the shop paid for a service it did not receive");
        assertEquals("ELIGIBLE", closed.note());
    }

    @Test
    @DisplayName("a suspended merchant is told WHY the fee did not come back")
    void ineligibilityIsExplained() {
        setTier(MerchantTier.SMALL);
        configurePlan("200.00", 250);
        jdbc.update("UPDATE merchants SET status = 'SUSPENDED' WHERE id = ?", merchantId);

        WeeklyBilling.WeekClosed closed = billing.closeWeek(merchantId, week, "billing-test");

        assertEquals(0, closed.feeRefunded().signum());
        assertTrue(closed.note().contains("NOT_IN_GOOD_STANDING"),
                "A MERCHANT TOLD \"YOU DID NOT QUALIFY\" WILL ASK WHY, and the answer has to be "
                        + "in the system rather than in whoever wrote the job: " + closed.note());
        assertEquals(new BigDecimal("200.00"), closed.payable());
    }

    // ------------------------------------------------------- idempotency

    @Test
    @DisplayName("closing the same week twice does not bill it twice")
    void closingIsIdempotent() {
        setTier(MerchantTier.SMALL);
        configurePlan("200.00", 250);
        deliveredOrder("1000.00", "0.00");

        WeeklyBilling.WeekClosed first = billing.closeWeek(merchantId, week, "billing-test");
        WeeklyBilling.WeekClosed again = billing.closeWeek(merchantId, week, "billing-test");

        assertEquals("ALREADY_CLOSED", again.note());
        assertEquals(first.payable(), again.payable(),
                "a retry, a second job instance or a person pressing it twice must see what the "
                        + "week already came to");
        assertEquals(first.payable(), billing.balanceOf(merchantId),
                "AND THE MERCHANT MUST NOT OWE IT TWICE. A weekly biller that runs twice is not "
                        + "a hypothetical - it is a redeploy during the job's window.");
    }

    // ------------------------------------------------------------------- §8

    @Test
    @DisplayName("a later refund gives the commission back, proportionally")
    void refundReversesCommission() {
        setTier(MerchantTier.SMALL);
        configurePlan("0.00", 1000);
        Long orderId = deliveredOrder("1000.00", "0.00");
        billing.closeWeek(merchantId, week, "billing-test");

        assertEquals(new BigDecimal("100.00"), billing.balanceOf(merchantId), "10% of 1000");

        billing.reverseCommission(orderId, new BigDecimal("400.00"), "billing-test");

        assertEquals(new BigDecimal("60.00"), billing.balanceOf(merchantId),
                "§8: MONEY THAT CAME BACK WAS NOT A SALE. 400 of 1000 refunded takes back 40% "
                        + "of the commission - the merchant did keep the rest of the sale.");

        MerchantLedgerEntry reversal = ledger.findByMerchantIdOrderByCreatedAtDesc(merchantId)
                .get(0);
        assertEquals(LedgerEntryType.COMMISSION_REVERSAL, reversal.getEntryType());
        assertTrue(reversal.getReversalOfId() != null,
                "a correction points at what it undoes, so the history reads as a story rather "
                        + "than as two unrelated numbers");
    }

    // --------------------------------------------------- the ledger's promise

    @Test
    @DisplayName("the database refuses to rewrite a ledger row")
    void theLedgerIsAppendOnly() {
        setTier(MerchantTier.SMALL);
        configurePlan("200.00", 250);
        billing.closeWeek(merchantId, week, "billing-test");
        Long entryId = ledger.findByMerchantIdOrderByCreatedAtDesc(merchantId).get(0).getId();

        DataAccessException updateRefused = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE merchant_ledger_entry SET amount = 1 WHERE id = ?",
                        entryId));
        assertTrue(updateRefused.getMessage().contains("append-only"),
                updateRefused.getMessage());

        DataAccessException deleteRefused = assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM merchant_ledger_entry WHERE id = ?", entryId));
        assertTrue(deleteRefused.getMessage().contains("append-only"),
                "A LEDGER THAT CAN BE EDITED IS A LEDGER WHOSE PAST IS A MATTER OF TRUST, and "
                        + "the entire reason §9 asks for one is to replace trust with a record. "
                        + "The database enforces it, not a code review.");
    }

    @Test
    @DisplayName("the balance is the sum of the rows, and is stored nowhere")
    void thereIsNoBalanceColumn() {
        Integer balanceColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND table_name IN ('merchants', 'billing_period') "
                        + "AND column_name IN ('balance', 'owed', 'outstanding')",
                Integer.class);
        assertEquals(0, balanceColumns,
                "§9: A STORED BALANCE IS A SECOND VERSION OF THE TRUTH that can drift from the "
                        + "first - and the first is the one with the dates, the reasons and the "
                        + "orders attached.");
    }

    // -------------------------------------------------------------- fixtures

    private void setTier(MerchantTier tier) {
        jdbc.update("UPDATE merchants SET tier = ? WHERE id = ?", tier.name(), merchantId);
    }

    private void configurePlan(String weeklyFee, int commissionBps) {
        jdbc.update("""
                INSERT INTO billing_plan (tier, weekly_fee, commission_bps, effective_from, created_by)
                VALUES ('SMALL', ?, ?, ?, 'billing-test')
                """, new BigDecimal(weeklyFee), commissionBps, java.sql.Date.valueOf(week.minusYears(1)));
        planId = jdbc.queryForObject(
                "SELECT max(id) FROM billing_plan WHERE created_by = 'billing-test'", Long.class);
    }

    /** A delivered order inside the billing week, with a delivery fee §8 excludes. */
    private Long deliveredOrder(String total, String deliveryFee) {
        String number = "BILL-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO orders (order_number, shop_id, order_date, total_amount, delivery_fee,
                                    order_status, payment_status)
                VALUES (?, ?, ?, ?, ?, 'DELIVERED', 'SUCCESS')
                """, number, shopId, java.sql.Timestamp.valueOf(week.plusDays(2).atTime(10, 0)),
                new BigDecimal(total), new BigDecimal(deliveryFee));
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?",
                Long.class, number);
    }
}
