package com.gpstore.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §6's ARITHMETIC, AND §5's PROMISE THAT NO AMOUNT IS DECIDED.
 *
 * <p>The model: a weekly fee P, commission C on successful product sales, and
 * the fee acting as a credit - so a merchant pays max(P, C). Below the fee
 * they pay the fee; above it they pay the commission and the fee is absorbed.
 *
 * <p>THE SECOND TEST IS THE UNUSUAL ONE and it is the one worth keeping. Part
 * 4 §5 says the commercial amounts are NOT finalized and must not be invented,
 * which is a promise about what is ABSENT from the code - and absence is not
 * something a behavioural test can demonstrate. So it reads the billing source
 * files and fails if a rupee figure has been written into one. The day
 * somebody puts "499" in a constant because it is convenient, this is what
 * catches it.
 */
@DisplayName("Billing arithmetic")
class BillingPlanArithmeticTest {

    /** Rates and fees come from here in real life; a test states its own. */
    private static BillingPlan plan(String weeklyFee, int commissionBps) {
        BillingPlan plan = new BillingPlan();
        plan.setTier(MerchantTier.SMALL);
        plan.setWeeklyFee(new BigDecimal(weeklyFee));
        plan.setCommissionBps(commissionBps);
        plan.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return plan;
    }

    /** What the three ledger rows come to: P + C - min(P, C). */
    private static BigDecimal weekCostsWith(BigDecimal fee, BigDecimal commission) {
        return fee.add(commission).subtract(fee.min(commission));
    }

    @Test
    @DisplayName("below the fee, the merchant pays the fee")
    void commissionUnderTheFee() {
        BillingPlan pricing = plan("200.00", 250);
        BigDecimal commission = pricing.commissionOn(new BigDecimal("1000.00"));

        assertEquals(new BigDecimal("25.00"), commission, "2.50% of 1000");
        assertEquals(new BigDecimal("200.00"),
                weekCostsWith(pricing.getWeeklyFee(), commission),
                "§6: if C < P the merchant pays P - the fee is a floor, and the commission it "
                        + "earned is credited against it rather than added to it");
    }

    @Test
    @DisplayName("at the fee, the merchant pays the fee once and not twice")
    void commissionExactlyTheFee() {
        BillingPlan pricing = plan("25.00", 250);
        BigDecimal commission = pricing.commissionOn(new BigDecimal("1000.00"));

        assertEquals(new BigDecimal("25.00"), commission);
        assertEquals(new BigDecimal("25.00"),
                weekCostsWith(pricing.getWeeklyFee(), commission),
                "THE CASE THE CREDIT EXISTS FOR. Without it the merchant would be charged the "
                        + "fee AND the commission the fee was meant to cover - which is exactly "
                        + "twice what §6 says they owe.");
    }

    @Test
    @DisplayName("above the fee, the merchant pays the commission and the fee is absorbed")
    void commissionOverTheFee() {
        BillingPlan pricing = plan("200.00", 250);
        BigDecimal commission = pricing.commissionOn(new BigDecimal("40000.00"));

        assertEquals(new BigDecimal("1000.00"), commission);
        assertEquals(new BigDecimal("1000.00"),
                weekCostsWith(pricing.getWeeklyFee(), commission),
                "§6: if C > P the merchant pays C. The weekly fee acted as a credit and is "
                        + "gone, not added on top.");
    }

    @Test
    @DisplayName("the rate is basis points, and rounds once, at two places")
    void roundingIsDecidedInOnePlace() {
        BillingPlan pricing = plan("0.00", 333);

        // 3.33% of 10.05 = 0.334665 -> 0.33
        assertEquals(new BigDecimal("0.33"), pricing.commissionOn(new BigDecimal("10.05")));
        // and it always comes back scaled, so a statement's rows sum to its total
        assertEquals(2, pricing.commissionOn(new BigDecimal("10.05")).scale());
        assertEquals(new BigDecimal("0.00"), pricing.commissionOn(BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.00"), pricing.commissionOn(null),
                "no sale is no commission, not a crash on the billing run");
        assertEquals(new BigDecimal("0.00"), pricing.commissionOn(new BigDecimal("-50.00")),
                "and a negative base earns nothing rather than paying the merchant");
    }

    @Test
    @DisplayName("a plan is the one in force on a date, so an old invoice still adds up")
    void plansAreVersionedByDate() {
        BillingPlan march = plan("200.00", 250);
        march.setEffectiveFrom(LocalDate.of(2026, 3, 1));
        march.setEffectiveTo(LocalDate.of(2026, 6, 1));

        assertTrue(march.coversDate(LocalDate.of(2026, 3, 1)), "inclusive at the start");
        assertTrue(march.coversDate(LocalDate.of(2026, 5, 31)));
        assertFalse(march.coversDate(LocalDate.of(2026, 6, 1)),
                "exclusive at the end, so the next plan's first day is not billed twice");
        assertFalse(march.coversDate(LocalDate.of(2026, 2, 28)));
    }

    @Test
    @DisplayName("no commercial amount is written into the billing code")
    void nothingIsHardCoded() throws IOException {
        // §5: THE AMOUNTS ARE NOT DECIDED, and this is a promise about what is
        // ABSENT - which no behavioural test can show. So it is checked
        // against the source: a money literal in these files is a price
        // somebody invented, and the day it appears this is what says so.
        Path billing = Path.of("src/main/java/com/gpstore/billing");
        Pattern moneyish = Pattern.compile(
                "(?<![\\w.])(?:new BigDecimal\\(\"\\d{2,}(?:\\.\\d+)?\"\\)"
                        + "|BigDecimal\\.valueOf\\(\\s*\\d{3,}"
                        + "|weeklyFee\\s*=\\s*\\d"
                        + "|commissionBps\\s*=\\s*\\d)");

        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(billing)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = moneyish.matcher(source);
                while (matcher.find()) {
                    String hit = matcher.group();
                    // 10000 is the basis-point denominator, not a price, and
                    // 12/2 are column widths. Named so the exception is a
                    // decision rather than a hole.
                    if (hit.contains("10_000") || hit.contains("10000")) {
                        continue;
                    }
                    offences.add(file.getFileName() + ": " + hit);
                }
            }
        }

        assertTrue(offences.isEmpty(),
                "PART 4 §5 SAYS THE COMMERCIAL AMOUNTS ARE NOT DECIDED. A fee or rate written "
                        + "into the code is a price nobody agreed to, and it will be the one "
                        + "that ships: " + offences);
    }
}
