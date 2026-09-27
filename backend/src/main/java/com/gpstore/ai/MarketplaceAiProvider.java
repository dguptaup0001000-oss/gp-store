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

    Optional<Intent> interpret(String prompt);

    /** DISABLED, HTTP, or another configured implementation name. */
    String name();
}
