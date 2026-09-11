package com.gpstore.policy;

import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.enums.OrderFault;
import com.gpstore.entity.Order;
import com.gpstore.order.cancellation.CancellationCharge;
import com.gpstore.order.cancellation.CancellationPolicy;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE NUMBERS NOBODY HAS DECIDED ARE NOT IN THE CODE.
 *
 * <p>WHY THIS NEEDS A TEST RATHER THAN A PROMISE. The commercial model is
 * deliberately unfinished: the weekly fee, the commission rate, the merchant
 * tiers and the cancellation ceiling are all founder decisions that have not
 * been made. An invented default for any of them does not announce itself -
 * it looks exactly like a finished feature, it passes every other test, and
 * the first person to find out is a merchant who was charged it.
 *
 * <p>SO THE ABSENCE IS ASSERTED. Three ways, because each catches a different
 * mistake:
 *
 * <ol>
 *   <li>the source of the commercial packages carries no rupee or percentage
 *       literal - catches somebody typing a number in;</li>
 *   <li>an undecided ceiling FAILS CLOSED at runtime - catches a default that
 *       looks like configuration but silently supplies an answer;</li>
 *   <li>the files carrying working defaults still say REQUIRES FOUNDER
 *       DECISION - catches somebody quietly promoting a placeholder to a
 *       policy by deleting the comment that said it was one.</li>
 * </ol>
 */
@DisplayName("No invented commercial amount")
class NoInventedCommercialAmountTest {

    private static final Path MAIN = Path.of("src/main/java/com/gpstore");

    // ---------------------------------------------------------- the source

    @Nested
    @DisplayName("nothing typed a number in")
    class TheSource {

        /**
         * Money-shaped literals in the packages that decide what a merchant pays.
         *
         * <p>Scale 2 decimals, and bare two-to-four digit whole numbers that
         * appear next to a currency word. Deliberately narrow: a test that
         * flagged every integer would be turned off within a week.
         */
        @Test
        @DisplayName("the billing package contains no money literal")
        void billingHasNoAmounts() throws Exception {
            List<String> found = moneyLiteralsUnder(MAIN.resolve("billing"));
            assertTrue(found.isEmpty(),
                    "A COMMERCIAL AMOUNT APPEARED IN THE BILLING CODE. The weekly fee, the "
                            + "commission rate and the tier rates are founder decisions that "
                            + "have not been made, and billing takes all of them as data. "
                            + "Found: " + found);
        }

        @Test
        @DisplayName("the cancellation package contains no charge rate")
        void cancellationHasNoRate() throws Exception {
            List<String> found = moneyLiteralsUnder(MAIN.resolve("order/cancellation"));
            assertTrue(found.isEmpty(),
                    "A CANCELLATION RATE APPEARED IN THE CODE. §10 gives the rate to the "
                            + "merchant and the ceiling to the platform's configuration; "
                            + "neither is a constant. Found: " + found);
        }

        private List<String> moneyLiteralsUnder(Path root) throws Exception {
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            // new BigDecimal("12.50") / ("199") - the shapes a rate or a fee
            // actually takes in this codebase. Scale-0 single digits are
            // excluded: ZERO, ONE and "100" for percent arithmetic are not
            // commercial amounts.
            Pattern money = Pattern.compile(
                    "new\\s+BigDecimal\\(\\s*\"(\\d+\\.\\d+|\\d{2,})\"\\s*\\)");
            List<String> offenders = new ArrayList<>();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    Matcher m = money.matcher(source);
                    while (m.find()) {
                        String literal = m.group(1);
                        // 100 is percent arithmetic, not a price.
                        if ("100".equals(literal)) {
                            continue;
                        }
                        offenders.add(file.getFileName() + " -> " + m.group());
                    }
                }
            }
            return offenders;
        }
    }

    // ------------------------------------------------------- fail closed

    @Nested
    @DisplayName("an undecided ceiling charges nothing")
    class FailsClosed {

        @Test
        @DisplayName("with no configured maximum, no shop can charge to cancel")
        void noCapMeansNoCharge() {
            StoreOperationsSettingsRepository settings =
                    mock(StoreOperationsSettingsRepository.class);

            StoreOperationsSettings shopWantsToCharge = new StoreOperationsSettings();
            shopWantsToCharge.setShopId(1L);
            shopWantsToCharge.setFreeCancellationSeconds(5);
            shopWantsToCharge.setCancellationFeePercent(new BigDecimal("2"));
            when(settings.findByShopId(any())).thenReturn(Optional.of(shopWantsToCharge));

            CancellationPolicy undecided = new CancellationPolicy(settings, null);

            assertFalse(undecided.capIsDecided(),
                    "an unset platform.cancellation.max-fee-percent must read as undecided");
            assertNull(undecided.maxFeePercent(),
                    "THERE IS NO DEFAULT CEILING. A default here would be GP-STORE choosing "
                            + "a commercial policy on the founder's behalf, which is exactly "
                            + "what the brief forbids.");

            Order order = new Order();
            order.setShopId(1L);
            order.setOrderDate(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
            order.setTotalAmount(new BigDecimal("600.00"));
            order.setDeliveryFee(BigDecimal.ZERO);

            CancellationCharge charge = undecided.quote(
                    order, OrderFault.CUSTOMER, LocalDateTime.now(ZoneOffset.UTC));

            assertTrue(charge.isFree(),
                    "A SHOP'S ROW SAID 2% AND THE PLATFORM HAD STATED NO MAXIMUM, and the "
                            + "customer was charged anyway. With no ceiling decided, the safe "
                            + "answer for the person who would pay it is nothing.");
        }

        @Test
        @DisplayName("a malformed ceiling reads as no ceiling, not as some other number")
        void aTypoDoesNotInventACap() {
            StoreOperationsSettingsRepository settings =
                    mock(StoreOperationsSettingsRepository.class);
            for (String broken : new String[]{"five", "-3", "900", ""}) {
                CancellationPolicy policy = new CancellationPolicy(settings, broken);
                assertFalse(policy.capIsDecided(),
                        "'" + broken + "' should read as undecided. Falling back to some "
                                + "other number would be inventing a second amount to cover "
                                + "for the first being unreadable.");
            }
        }
    }

    // ------------------------------------------ the placeholders say so

    @Nested
    @DisplayName("working defaults still admit they are placeholders")
    class StillMarked {

        /**
         * The files that carry a threshold nobody signed off.
         *
         * <p>These DO have numbers in them, legitimately: a governance ladder
         * with no expiry and a trust badge with no threshold are not safer,
         * they are broken. What matters is that the number is a fallback
         * behind configuration and that the code says so - so this test
         * fails if the marker is removed, which is what "quietly promoting a
         * placeholder to a policy" looks like in a diff.
         */
        @Test
        @DisplayName("governance and reliability thresholds are still marked undecided")
        void themarkerSurvives() throws Exception {
            for (String file : List.of(
                    "governance/MerchantGovernance.java",
                    "platform/ShopReliability.java",
                    "order/cancellation/CancellationPolicy.java")) {
                String source = Files.readString(MAIN.resolve(file));
                assertTrue(source.contains("REQUIRES FOUNDER DECISION"),
                        file + " no longer says REQUIRES FOUNDER DECISION. Either somebody "
                                + "made the decision - in which case record it somewhere "
                                + "better than a deleted comment - or a placeholder has "
                                + "quietly become a policy.");
            }
        }

        @Test
        @DisplayName("the thresholds are configurable, not compile-time constants")
        void thresholdsAreConfigurable() throws Exception {
            String governance = Files.readString(MAIN.resolve("governance/MerchantGovernance.java"));
            assertTrue(governance.contains("governance.warning-days"),
                    "the warning window must be readable from configuration");
            assertFalse(governance.contains("public static final int WARNING_DAYS"),
                    "A PUBLIC CONSTANT IS AN API. Callers compile against it, and the "
                            + "threshold then cannot change without a release - which is the "
                            + "opposite of configurable.");

            String reliability = Files.readString(MAIN.resolve("platform/ShopReliability.java"));
            assertTrue(reliability.contains("reliability.min-orders"),
                    "what TRUSTED costs must be readable from configuration");
        }
    }
}
