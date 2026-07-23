package com.gpstore.service;

import com.gpstore.entity.ProductVariant;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class TaxService {

    /**
     * Indian retail convention: the selling price (like MRP) is GST-inclusive,
     * not added on top at checkout. So this returns the rate to use for
     * reporting/invoicing purposes - it does NOT change what the customer pays.
     * Precedence: variant-level override, then the variant's category rate,
     * then 0% if neither is set (never guessed).
     */
    public BigDecimal resolveGstRate(ProductVariant variant) {
        if (variant.getGstRateOverride() != null) {
            return variant.getGstRateOverride();
        }

        if (variant.getProduct() != null
                && variant.getProduct().getCategory() != null
                && variant.getProduct().getCategory().getGstRate() != null) {
            return variant.getProduct().getCategory().getGstRate();
        }

        return BigDecimal.ZERO;
    }

    /**
     * Extracts the GST already included in a price, rather than adding tax on
     * top. E.g. a ₹105 item with a 5% GST-inclusive rate contains ₹5 of tax:
     * 105 - (105 / 1.05) = 5.00.
     */
    public BigDecimal extractTaxComponent(BigDecimal inclusiveAmount, BigDecimal gstRatePercent) {
        if (inclusiveAmount == null || gstRatePercent == null || gstRatePercent.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal divisor = BigDecimal.ONE.add(gstRatePercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        BigDecimal exclusiveAmount = inclusiveAmount.divide(divisor, 6, RoundingMode.HALF_UP);

        return inclusiveAmount.subtract(exclusiveAmount).setScale(2, RoundingMode.HALF_UP);
    }
}
