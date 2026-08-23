package com.gpstore.pricing;

import com.gpstore.entity.DeliveryPricingSettings;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading a weight off a shelf that has no weight column.
 *
 * The catalogue records a pack quantity and a unit, and this turns that into
 * kilograms. The units here are the REAL ones from the catalogue - g (557
 * variants), kg (172), ml (150), l (98), pc (588), pcs (37), L (8) - rather
 * than a tidy invented set, because the case that matters is the one the shop
 * actually has.
 */
class OrderWeightCalculatorTest {

    private DeliveryPricingSettings settings(String assumedPerItemKg) {
        DeliveryPricingSettings s = new DeliveryPricingSettings();
        s.setAssumedWeightPerItemKg(new BigDecimal(assumedPerItemKg));
        s.normalise();
        return s;
    }

    private ProductVariant variant(Double quantity, String unit, String sku) {
        ProductVariant v = new ProductVariant();
        v.setQuantity(quantity);
        v.setUnit(unit);
        v.setSku(sku);
        Product p = new Product();
        p.setName("Test product");
        v.setProduct(p);
        return v;
    }

    @ParameterizedTest(name = "{0} {1} weighs {2} kg")
    @CsvSource({
            "5,    kg,  5",
            "1,    kg,  1",
            "500,  g,   0.5",
            "250,  g,   0.25",
            "1,    l,   1",
            "1,    L,   1",
            "500,  ml,  0.5",
            "5,    KG,  5",
            "5,    Kg,  5",
            "2,    litre, 2",
    })
    @DisplayName("mass and volume units are read straight off the pack size")
    void derivesFromPackSize(double quantity, String unit, String expectedKg) {
        BigDecimal actual = OrderWeightCalculator.unitWeightKg(variant(quantity, unit, "SKU"));
        assertNotNull(actual, unit + " should be derivable");
        assertEquals(0, new BigDecimal(expectedKg).compareTo(actual),
                quantity + " " + unit + " should be " + expectedKg + " kg but was " + actual);
    }

    @ParameterizedTest
    @CsvSource({"pc", "pcs", "piece", "pack", "dozen", "nos", "''"})
    @DisplayName("piece-counted units are unknown, not zero")
    void piecesAreUnknown(String unit) {
        // Null and zero must stay distinguishable: only one of them is worth
        // telling the shop about.
        assertNull(OrderWeightCalculator.unitWeightKg(variant(1.0, unit, "SKU")),
                "'" + unit + "' is a count, not a mass");
    }

    @Test
    @DisplayName("an explicit weight on the variant beats the pack size")
    void weightGramsOverridesEverything() {
        // The escape hatch for the cases the pack size gets wrong - a 1 l
        // bottle of oil, a 12-piece box of soap.
        ProductVariant v = variant(1.0, "l", "OIL-1L");
        v.setWeightGrams(new BigDecimal("920"));

        assertEquals(0, new BigDecimal("0.92").compareTo(OrderWeightCalculator.unitWeightKg(v)));
    }

    @Test
    @DisplayName("the order's weight is the sum over its lines, times quantities")
    void totalsAcrossTheBasket() {
        var result = OrderWeightCalculator.totalWeightKg(List.of(
                new OrderWeightCalculator.Line(variant(5.0, "kg", "ATTA-5"), 2),   // 10
                new OrderWeightCalculator.Line(variant(500.0, "g", "HALDI"), 4),   // 2
                new OrderWeightCalculator.Line(variant(1.0, "l", "OIL"), 1)        // 1
        ), settings("0"));

        assertEquals(0, new BigDecimal("13").compareTo(result.totalKg()));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("items with no derivable weight are named, not silently ignored")
    void unknownWeightsAreReported() {
        // §12: never silently assume missing data. Zero is the safe number to
        // use - a fabricated weight charges a real customer - but it must not
        // be a silent zero, or nobody ever fills the gap in.
        var result = OrderWeightCalculator.totalWeightKg(List.of(
                new OrderWeightCalculator.Line(variant(5.0, "kg", "ATTA-5"), 1),
                new OrderWeightCalculator.Line(variant(1.0, "pc", "SOAP-BAR"), 3)
        ), settings("0"));

        assertEquals(0, new BigDecimal("5").compareTo(result.totalKg()),
                "the unknown item contributes nothing rather than a guess");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("SOAP-BAR"),
                "the warning has to name the item so somebody can fix it: " + result.warnings());
    }

    @Test
    @DisplayName("the shop can set an assumed weight for piece items")
    void assumedWeightIsConfigurable() {
        var result = OrderWeightCalculator.totalWeightKg(List.of(
                new OrderWeightCalculator.Line(variant(1.0, "pc", "SOAP-BAR"), 4)
        ), settings("0.250"));

        assertEquals(0, new BigDecimal("1.000").compareTo(result.totalKg()));
        assertTrue(result.warnings().get(0).contains("0.250"),
                "the assumption should be stated, not hidden: " + result.warnings());
    }

    @Test
    @DisplayName("a missing or nonsensical pack size is unknown rather than an exception")
    void badDataDoesNotThrow() {
        assertNull(OrderWeightCalculator.unitWeightKg(variant(null, "kg", "X")));
        assertNull(OrderWeightCalculator.unitWeightKg(variant(0.0, "kg", "X")));
        assertNull(OrderWeightCalculator.unitWeightKg(variant(-5.0, "kg", "X")));

        ProductVariant noUnit = variant(5.0, null, "X");
        assertNull(OrderWeightCalculator.unitWeightKg(noUnit));
    }

    @Test
    @DisplayName("an empty basket weighs nothing and says nothing")
    void emptyIsFine() {
        var result = OrderWeightCalculator.totalWeightKg(List.of(), settings("0"));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalKg()));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("a null variant or a zero quantity is skipped, not counted")
    void skipsJunkLines() {
        var result = OrderWeightCalculator.totalWeightKg(java.util.Arrays.asList(
                new OrderWeightCalculator.Line(null, 5),
                new OrderWeightCalculator.Line(variant(5.0, "kg", "ATTA"), 0),
                new OrderWeightCalculator.Line(variant(2.0, "kg", "RICE"), 1)
        ), settings("0"));

        assertEquals(0, new BigDecimal("2").compareTo(result.totalKg()));
    }
}
