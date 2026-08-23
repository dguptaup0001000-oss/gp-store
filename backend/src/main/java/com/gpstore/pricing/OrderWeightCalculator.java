package com.gpstore.pricing;

import com.gpstore.entity.DeliveryPricingSettings;
import com.gpstore.entity.ProductVariant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an order weighs.
 *
 * THERE IS NO WEIGHT COLUMN IN THE CATALOGUE, and inventing one to fill by
 * hand for sixteen hundred variants would have been a data-entry project
 * rather than a feature. What the catalogue does have is a pack quantity and a
 * unit, and for most of the shelf that IS the weight: 557 variants are sold in
 * grams, 172 in kilograms, 256 in millilitres or litres.
 *
 * So this reads the shelf in the order a shopkeeper would:
 *
 *   1. weightGrams on the variant, if somebody has filled it in. Always wins.
 *   2. The pack quantity and unit, for anything sold by mass or volume.
 *   3. The configured assumption, for anything sold by the piece - zero by
 *      default, and NAMED in the warnings so the gap is a list to work
 *      through rather than a silent under-charge.
 *
 * VOLUME IS TREATED AS WATER. A litre of oil is about 0.92 kg and a litre of
 * milk about 1.03; calling both 1.00 is wrong by a few percent on an item, and
 * the surcharge it feeds is ₹2 per kilogram above ten with a ₹20 ceiling. The
 * error cannot reach a rupee on any realistic basket, and the alternative -
 * a density table for a kirana shop - is a great deal of machinery for less
 * than the rounding.
 */
public final class OrderWeightCalculator {

    /** One line item: what it is, and how many of it. */
    public record Line(ProductVariant variant, int quantity) {
    }

    public record WeightResult(BigDecimal totalKg, List<String> warnings) {
    }

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private OrderWeightCalculator() {
    }

    public static WeightResult totalWeightKg(List<Line> lines, DeliveryPricingSettings settings) {
        DeliveryPricingSettings s = settings == null ? new DeliveryPricingSettings() : settings;
        s.normalise();

        BigDecimal total = BigDecimal.ZERO;
        List<String> unknown = new ArrayList<>();

        for (Line line : lines) {
            if (line == null || line.variant() == null || line.quantity() <= 0) {
                continue;
            }
            ProductVariant variant = line.variant();
            BigDecimal unitKg = unitWeightKg(variant);

            if (unitKg == null) {
                unitKg = s.getAssumedWeightPerItemKg();
                String label = describe(variant);
                if (!unknown.contains(label)) {
                    unknown.add(label);
                }
            }

            total = total.add(unitKg.multiply(BigDecimal.valueOf(line.quantity())));
        }

        List<String> warnings = new ArrayList<>();
        if (!unknown.isEmpty()) {
            // Named, and capped at a readable number - a warning listing two
            // hundred SKUs is one nobody reads, and the point is that somebody
            // acts on it.
            List<String> shown = unknown.size() > 8 ? unknown.subList(0, 8) : unknown;
            warnings.add("No weight is recorded for " + unknown.size() + " item(s), so "
                    + (s.getAssumedWeightPerItemKg().signum() == 0
                        ? "they were counted as weighing nothing"
                        : "each was assumed to weigh " + s.getAssumedWeightPerItemKg() + " kg")
                    + ": " + String.join(", ", shown)
                    + (unknown.size() > shown.size() ? ", and others" : "")
                    + ". Set a weight on these variants to price them correctly.");
        }

        return new WeightResult(total, warnings);
    }

    /**
     * One unit of this variant, in kilograms, or null when nothing says.
     *
     * Null is a real answer here and is deliberately not zero: the caller has
     * to be able to tell "this weighs nothing" from "nobody knows what this
     * weighs", because only one of those is worth telling the shop about.
     */
    static BigDecimal unitWeightKg(ProductVariant variant) {
        if (variant.getWeightGrams() != null && variant.getWeightGrams().signum() > 0) {
            return variant.getWeightGrams().divide(THOUSAND, 6, java.math.RoundingMode.HALF_UP);
        }

        BigDecimal packSize = variant.getQuantity() == null
                ? null : BigDecimal.valueOf(variant.getQuantity());
        if (packSize == null || packSize.signum() <= 0) {
            return null;
        }

        String unit = variant.getUnit() == null
                ? "" : variant.getUnit().trim().toLowerCase(Locale.ROOT);

        return switch (unit) {
            case "kg", "kgs", "kilogram", "kilograms" -> packSize;
            case "g", "gm", "gms", "gram", "grams" -> packSize.divide(THOUSAND, 6, java.math.RoundingMode.HALF_UP);
            // Volume as water - see the class comment for why that is close
            // enough at this scale.
            case "l", "ltr", "litre", "litres", "liter", "liters" -> packSize;
            case "ml", "millilitre", "millilitres", "milliliter", "milliliters" ->
                    packSize.divide(THOUSAND, 6, java.math.RoundingMode.HALF_UP);
            // Pieces, packs, dozens - the quantity is a count, not a mass.
            default -> null;
        };
    }

    private static String describe(ProductVariant variant) {
        if (variant.getSku() != null && !variant.getSku().isBlank()) {
            return variant.getSku();
        }
        if (variant.getProduct() != null && variant.getProduct().getName() != null) {
            return variant.getProduct().getName();
        }
        return "variant " + variant.getId();
    }
}
