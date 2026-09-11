package com.gpstore.governance;

import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
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
import com.gpstore.platform.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A LADDER, NOT A SWITCH (Part 4 §2).
 *
 * <p>WHAT EXISTED BEFORE. merchants.status could be set to SUSPENDED by a
 * platform admin. That is the whole of it: no reason a shopkeeper could read,
 * nothing between "fine" and "your business is shut", no record of who did it
 * or why, and no way to answer back. Every one of those gaps is a way for a
 * real kirana to lose its income to a mistake nobody can find afterwards.
 *
 * <p>WHAT THESE TESTS HOLD IN PLACE. That the rungs cannot be skipped; that
 * the narrow exception is narrow and is about harm to customers rather than
 * to GP-STORE's revenue; that suspending actually stops trade and lifting
 * actually restores it; that an appeal is answered once and that winning one
 * reopens the shop; and that warnings decay, because a ladder with no way
 * down is one every long-lived merchant eventually falls off.
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
@DisplayName("A ladder, not a switch")
class ALadderNotASwitchTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private MerchantGovernance governance;

    private final String tag = "gov" + System.nanoTime();

    private Long merchantId;
    private long shopId;

    @BeforeEach
    void aMerchantWithOneShop() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Merchant m = new Merchant();
        m.setLegalName("Governance fixture " + tag);
        m.setDisplayName("Governance fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        Shop s = new Shop();
        s.setMerchantId(merchantId);
        s.setCode("GOV-" + tag);
        s.setDisplayName("The shop under review");
        s.setStatus(ShopStatus.ACTIVE);
        s.setLatitude(27.16);
        s.setLongitude(83.94);
        s.setMaxDeliveryRadiusKm(new BigDecimal("5"));
        s.setTimeZone("Asia/Kolkata");
        s.setIsDemo(Boolean.TRUE);
        s.setActive(Boolean.TRUE);
        shopId = shops.save(s).getId();
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM merchant_governance_actions WHERE merchant_id = ?", merchantId);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
        jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        // Back to SINGLE_SHOP, not to this class's own mode - TenantDefaults
        // is a static holder shared by every cached context in the run.
        TenantDefaults.install(PlatformMode.SINGLE_SHOP,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    // -------------------------------------------------------- the ladder

    @Nested
    @DisplayName("the rungs cannot be skipped")
    class TheLadder {

        @Test
        @DisplayName("a suspension needs a final warning behind it")
        void noSuspensionOutOfNowhere() {
            ConflictException refused = assertThrows(ConflictException.class,
                    () -> platform(() -> governance.issue(merchantId, null,
                            GovernanceLevel.SUSPENSION, GovernanceReason.REPEATED_CANCELLATIONS,
                            "14 of the last 40 orders cancelled after acceptance", "reviewer")));

            assertTrue(refused.getMessage().contains("FINAL_WARNING"),
                    "and it says what is missing: " + refused.getMessage());

            assertEquals(MerchantStatus.ACTIVE,
                    merchants.findById(merchantId).orElseThrow().getStatus(),
                    "A SUSPENSION THAT WAS REFUSED MUST NOT HAVE HAPPENED HALFWAY. The record "
                            + "and the effect are written together or not at all.");
        }

        @Test
        @DisplayName("a final warning needs a warning behind it")
        void noFinalWarningOutOfNowhere() {
            assertThrows(ConflictException.class,
                    () -> platform(() -> governance.issue(merchantId, null,
                            GovernanceLevel.FINAL_WARNING, GovernanceReason.UNDELIVERED_ORDERS,
                            "three undelivered", "reviewer")));
        }

        @Test
        @DisplayName("climbed in order, each rung is allowed")
        void theLadderWorksInOrder() {
            platform(() -> governance.issue(merchantId, null, GovernanceLevel.WARNING,
                    GovernanceReason.REPEATED_CANCELLATIONS, "first", "reviewer"));
            assertEquals(GovernanceLevel.WARNING, governance.standing(merchantId).level());

            platform(() -> governance.issue(merchantId, null, GovernanceLevel.FINAL_WARNING,
                    GovernanceReason.REPEATED_CANCELLATIONS, "again", "reviewer"));
            assertEquals(GovernanceLevel.FINAL_WARNING, governance.standing(merchantId).level());

            platform(() -> governance.issue(merchantId, null, GovernanceLevel.SUSPENSION,
                    GovernanceReason.REPEATED_CANCELLATIONS, "and again", "reviewer"));

            MerchantStanding standing = governance.standing(merchantId);
            assertEquals(GovernanceLevel.SUSPENSION, standing.level());
            assertFalse(standing.mayTrade());
        }

        @Test
        @DisplayName("unsafe goods skip the ladder; owing GP-STORE money does not")
        void theExceptionIsAboutHarmNotRevenue() {
            platform(() -> governance.issue(merchantId, null, GovernanceLevel.SUSPENSION,
                    GovernanceReason.COUNTERFEIT_OR_UNSAFE_GOODS,
                    "Expired medicine on the shelf", "reviewer"));
            assertFalse(governance.standing(merchantId).mayTrade(),
                    "THE LADDER EXISTS SO A LIVELIHOOD CANNOT END ON A WHIM, not so that a "
                            + "shop selling unsafe goods gets a polite note first.");

            assertFalse(GovernanceReason.UNPAID_PLATFORM_DUES.allowsImmediateSuspension(),
                    "A PLATFORM THAT CAN SUSPEND A SHOP FOR OWING IT MONEY without a warning "
                            + "first has a governance ladder that is really a collection "
                            + "mechanism.");
            assertFalse(GovernanceReason.UNPAID_REFUNDS.allowsImmediateSuspension());
            assertTrue(GovernanceReason.REGULATORY_NON_COMPLIANCE.allowsImmediateSuspension());
            assertTrue(GovernanceReason.LEGAL_ORDER.allowsImmediateSuspension());
        }
    }

    // ------------------------------------------------------- the effect

    @Nested
    @DisplayName("a suspension actually stops trade, and lifting it restores it")
    class TheEffect {

        @Test
        @DisplayName("suspending the merchant shuts their shops")
        void suspensionReachesTheShops() {
            suspendForSafety();

            assertEquals(MerchantStatus.SUSPENDED,
                    merchants.findById(merchantId).orElseThrow().getStatus());
            assertEquals(ShopStatus.SUSPENDED, statusOfShop(),
                    "A RECORD WITHOUT AN EFFECT would leave a \"suspended\" merchant still "
                            + "taking orders.");
        }

        @Test
        @DisplayName("reinstating is its own step, and it reopens the shop")
        void reinstatementIsRecorded() {
            MerchantGovernanceAction suspension = suspendForSafety();

            MerchantGovernanceAction lifting = platform(() ->
                    governance.reinstate(suspension.getId(), "Stock withdrawn and checked", "reviewer"));

            assertEquals(GovernanceLevel.REINSTATEMENT, lifting.getLevel());
            assertEquals(suspension.getId(), lifting.getSupersedesId(),
                    "\"SUSPENDED, THEN REINSTATED\" IS ONE STORY. Deleting the suspension "
                            + "would leave a gap where the story was.");
            assertEquals(MerchantStatus.ACTIVE,
                    merchants.findById(merchantId).orElseThrow().getStatus());
            assertEquals(ShopStatus.ACTIVE, statusOfShop());
            assertTrue(governance.standing(merchantId).mayTrade());
            assertTrue(governance.standing(merchantId).isClean(),
                    "a superseded action stops counting");
        }

        @Test
        @DisplayName("a warning stops counting once it has expired")
        void warningsDecay() {
            MerchantGovernanceAction warning = platform(() ->
                    governance.issue(merchantId, null, GovernanceLevel.WARNING,
                            GovernanceReason.REPEATED_CANCELLATIONS, "a bad fortnight", "reviewer"));

            assertNotNull(warning.getExpiresAt(),
                    "A LADDER WITH NO WAY DOWN is one every long-lived merchant eventually "
                            + "falls off, for something that happened two years ago.");
            assertEquals(GovernanceLevel.WARNING,
                    governance.standing(merchantId, warning.getIssuedAt().plusDays(1)).level());
            assertTrue(governance.standing(merchantId,
                    warning.getIssuedAt().plusDays(governance.warningDays() + 1)).isClean(),
                    "and ninety days later the record is clean again");
        }
    }

    // -------------------------------------------------------- the appeal

    @Nested
    @DisplayName("§2 the merchant can answer back")
    class TheAppeal {

        @Test
        @DisplayName("one appeal each, and a second is refused")
        void oneAppealEach() {
            MerchantGovernanceAction suspension = suspendForSafety();

            platform(() -> governance.appeal(suspension.getId(), merchantId,
                    "The batch was withdrawn by the supplier, not sold by us"));

            assertThrows(ConflictException.class,
                    () -> platform(() -> governance.appeal(suspension.getId(), merchantId, "again")),
                    "A MERCHANT WHO COULD RE-APPEAL INDEFINITELY could keep a suspension "
                            + "permanently \"under review\".");

            assertEquals(1, governance.standing(merchantId).openAppeals().size(),
                    "and it shows as waiting, because somebody is waiting");
        }

        @Test
        @DisplayName("somebody else's action is not yours to appeal, and you cannot find out it exists")
        void onlyYourOwnRecord() {
            MerchantGovernanceAction suspension = suspendForSafety();
            assertThrows(ResourceNotFoundException.class,
                    () -> platform(() -> governance.appeal(
                            suspension.getId(), merchantId + 999_999L, "not mine")));
        }

        @Test
        @DisplayName("winning an appeal reopens the shop")
        void overturningLiftsTheEffect() {
            MerchantGovernanceAction suspension = suspendForSafety();
            platform(() -> governance.appeal(suspension.getId(), merchantId, "It was not us"));

            platform(() -> governance.decideAppeal(suspension.getId(),
                    AppealOutcome.OVERTURNED, "Supplier recall, not the shop's doing", "reviewer"));

            assertEquals(MerchantStatus.ACTIVE,
                    merchants.findById(merchantId).orElseThrow().getStatus(),
                    "A MERCHANT WHO WINS AN APPEAL AND STAYS SHUT has not won anything.");
            assertEquals(ShopStatus.ACTIVE, statusOfShop());
            assertTrue(governance.standing(merchantId).isClean());
        }

        @Test
        @DisplayName("a reduced appeal comes down one rung and reopens the shop")
        void reducingComesDownARung() {
            MerchantGovernanceAction suspension = suspendForSafety();
            platform(() -> governance.appeal(suspension.getId(), merchantId, "Too heavy"));

            MerchantGovernanceAction decided = platform(() -> governance.decideAppeal(
                    suspension.getId(), AppealOutcome.REDUCED,
                    "It happened, but a final warning covers it", "reviewer"));

            assertEquals(GovernanceLevel.FINAL_WARNING, decided.getLevel(),
                    "REDUCED IS THE ANSWER THAT LETS A REVIEWER BE HONEST - the conduct "
                            + "happened, the punishment was heavier than it needed to be - "
                            + "without pretending it did not happen or leaving the shop shut.");
            assertEquals(ShopStatus.ACTIVE, statusOfShop());
            assertEquals(GovernanceLevel.FINAL_WARNING, governance.standing(merchantId).level());
        }

        @Test
        @DisplayName("an upheld appeal changes nothing, and is decided only once")
        void upholdingLeavesItStanding() {
            MerchantGovernanceAction suspension = suspendForSafety();
            platform(() -> governance.appeal(suspension.getId(), merchantId, "Not fair"));
            platform(() -> governance.decideAppeal(suspension.getId(),
                    AppealOutcome.UPHELD, "The evidence stands", "reviewer"));

            assertEquals(ShopStatus.SUSPENDED, statusOfShop());
            assertThrows(ConflictException.class,
                    () -> platform(() -> governance.decideAppeal(suspension.getId(),
                            AppealOutcome.OVERTURNED, "changed my mind", "reviewer")));
        }

        @Test
        @DisplayName("an action nobody appealed cannot be decided")
        void nothingToDecide() {
            MerchantGovernanceAction suspension = suspendForSafety();
            assertThrows(ConflictException.class,
                    () -> platform(() -> governance.decideAppeal(suspension.getId(),
                            AppealOutcome.OVERTURNED, "on my own initiative", "reviewer")));
        }
    }

    // ---------------------------------------------------- transparency

    @Nested
    @DisplayName("§2 the merchant can read their own record and nobody else's")
    class Transparency {

        @Test
        @DisplayName("the shop's own record carries the reason and the evidence")
        void theRecordIsReadable() {
            platform(() -> governance.issue(merchantId, null, GovernanceLevel.WARNING,
                    GovernanceReason.REPEATED_CANCELLATIONS,
                    "14 of your last 40 orders were cancelled after acceptance", "reviewer"));

            var mine = inShop(shopId, () -> governance.historyForShop());
            assertEquals(1, mine.size());
            assertEquals(GovernanceReason.REPEATED_CANCELLATIONS, mine.get(0).getReasonCode());
            assertTrue(mine.get(0).getDetail().contains("14 of your last 40"),
                    "A REASON CODE ALONE IS A FILING CATEGORY. \"14 of your last 40 orders\" "
                            + "is something a shopkeeper can act on or dispute.");
        }

        @Test
        @DisplayName("the merchant is derived from the shop in scope, never named in a request")
        void theMerchantIsNeverNamed() {
            assertEquals(merchantId, inShop(shopId, () -> governance.merchantOfShopInScope()));
            assertEquals(null, TenantContext.runWithin(TenantScope.platform(),
                    () -> governance.merchantOfShopInScope()),
                    "and a platform caller has no merchant of their own, so the self-service "
                            + "read returns nothing rather than somebody's record");
        }
    }

    // ------------------------------------------------------------- helpers

    private MerchantGovernanceAction suspendForSafety() {
        return platform(() -> governance.issue(merchantId, null, GovernanceLevel.SUSPENSION,
                GovernanceReason.COUNTERFEIT_OR_UNSAFE_GOODS,
                "Expired stock found on the shelf", "reviewer"));
    }

    private ShopStatus statusOfShop() {
        return TenantContext.runWithin(TenantScope.platform(),
                () -> shops.findById(shopId).orElseThrow().getStatus());
    }

    private <T> T platform(java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.platform(), work::get);
    }

    private <T> T inShop(long shop, java.util.function.Supplier<T> work) {
        return TenantContext.runWithin(TenantScope.ofShop(shop), work::get);
    }
}
