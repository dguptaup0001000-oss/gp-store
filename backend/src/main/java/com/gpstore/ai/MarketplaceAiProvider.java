package com.gpstore.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provider seam only interprets language. It never receives authority to read
 * prices, stock, shops, orders, or to publish catalogue data.
 */
public interface MarketplaceAiProvider {
    record Intent(String query, Long categoryId, BigDecimal budget, Integer quantity,
                  String commerceMode, Map<String, String> attributes,
                  List<String> requiredItems, String language) {}
    record CatalogCandidate(String name, String brand, String description,
                            String variantLabel, Double quantity, String unit,
                            BigDecimal mrp, BigDecimal sellingPrice, Integer stock,
                            String barcode, BigDecimal confidence,
                            List<String> generatedFields,
                            List<String> uncertainFields) {}

    Optional<Intent> interpret(String prompt);

    /**
     * Extracts only values visible in a controlled image/document. Empty means
     * unavailable or uncertain; publication remains a separate merchant action.
     */
    default List<CatalogCandidate> extractCatalog(String sourceType, String controlledUrl) {
        return List.of();
    }

    /** DISABLED, HTTP, or another configured implementation name. */
    String name();
}
