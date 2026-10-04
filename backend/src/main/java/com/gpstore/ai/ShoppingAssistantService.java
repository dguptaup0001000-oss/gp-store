package com.gpstore.ai;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.platform.api.MarketplaceFeedService;
import com.gpstore.platform.api.MarketplaceFeedView;
import com.gpstore.search.SearchNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Interpretation is separated from retrieval: every recommendation below is
 * a row returned by the marketplace database, never provider prose.
 */
@Service
public class ShoppingAssistantService {
    private final MarketplaceAiProvider provider;
    private final MarketplaceFeedService marketplace;
    private final FallbackAiProvider fallback;
    private final boolean enabled;

    public ShoppingAssistantService(MarketplaceAiProvider provider,
                                    MarketplaceFeedService marketplace,
                                    @Value("${marketplace.ai.enabled:false}") boolean enabled) {
        this(provider, marketplace, new FallbackAiProvider(), enabled);
    }

    @Autowired
    public ShoppingAssistantService(MarketplaceAiProvider provider,
                                    MarketplaceFeedService marketplace,
                                    FallbackAiProvider fallback,
                                    @Value("${marketplace.ai.enabled:false}") boolean enabled) {
        this.provider = provider;
        this.marketplace = marketplace;
        this.fallback = fallback;
        this.enabled = enabled;
    }

    public record Request(String prompt, Double latitude, Double longitude) {}
    public record BasketLine(String requestedItem, int quantity, MarketplaceFeedView offer) {}
    public record Answer(MarketplaceAiProvider.Intent intent, String interpreter,
                         boolean aiEnabled, List<MarketplaceFeedView> results,
                         List<BasketLine> suggestedBasket, List<String> unavailableItems,
                         BigDecimal estimatedSubtotal, boolean requiresCustomerReview) {}

    public Answer answer(Request request) {
        MarketplaceAiProvider.Intent intent = provider.interpret(request == null ? null : request.prompt())
                .orElseThrow(() -> new com.gpstore.exception.BadRequestException(
                        "Tell GP-STORE what you need."));
        MarketplaceAiProvider.Intent localIntent = fallback
                .interpret(request == null ? null : request.prompt()).orElse(intent);
        Set<CommerceMode> modes = parseMode(intent.commerceMode());
        List<String> items = !intent.requiredItems().isEmpty()
                ? intent.requiredItems()
                : !localIntent.requiredItems().isEmpty()
                        ? localIntent.requiredItems()
                        : List.of(localIntent.query());
        BigDecimal budget = intent.budget() == null ? localIntent.budget() : intent.budget();
        List<BasketLine> basket = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<MarketplaceFeedView> direct = List.of();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (String item : items) {
            String inventoryQuery = items.size() == 1
                    ? withAttributes(item, intent.attributes()) : item;
            List<MarketplaceFeedView> found = marketplace.search(inventoryQuery, request.latitude(),
                            request.longitude(), modes, 0, 10).stream()
                    .filter(card -> matchesRequestedItem(item, card))
                    .toList();
            if (items.size() == 1) direct = found;
            MarketplaceFeedView choice = found.stream()
                    .filter(card -> card.sellingPrice() != null)
                    .findFirst().orElse(null);
            if (choice == null) {
                unavailable.add(item);
                continue;
            }
            BigDecimal next = subtotal.add(choice.sellingPrice());
            if (budget != null && next.compareTo(budget) > 0) {
                unavailable.add(item + " (outside remaining budget)");
                continue;
            }
            basket.add(new BasketLine(item, 1, choice));
            subtotal = next;
        }
        return new Answer(intent, provider.name(), enabled, direct, List.copyOf(basket),
                List.copyOf(unavailable), subtotal, true);
    }

    /**
     * Search metadata can be broad or stale. A candidate is only a suggestion
     * when its customer-visible product, brand, or category agrees with at
     * least one meaningful requested word. The phonetic key preserves common
     * transliteration and typo matches such as "briyani" / "biryani".
     */
    private static boolean matchesRequestedItem(String requestedItem, MarketplaceFeedView card) {
        if (requestedItem == null || card == null) return false;
        List<String> requested = SearchNormalizer.tokenize(requestedItem).stream()
                .filter(token -> token.length() >= 3)
                .toList();
        if (requested.isEmpty()) return true;
        List<String> visible = SearchNormalizer.tokenize(String.join(" ",
                card.name() == null ? "" : card.name(),
                card.brand() == null ? "" : card.brand(),
                card.categoryName() == null ? "" : card.categoryName()));
        for (String wanted : requested) {
            String key = SearchNormalizer.phoneticKey(wanted);
            for (String actual : visible) {
                if (wanted.equals(actual)) return true;
                if (!key.isEmpty() && key.equals(SearchNormalizer.phoneticKey(actual))) return true;
                if ((wanted.equals("phone") || wanted.equals("mobile"))
                        && List.of("phone", "mobile", "smartphone", "iphone", "android")
                                .contains(actual)) return true;
            }
        }
        return false;
    }

    private static String withAttributes(String query, java.util.Map<String, String> attributes) {
        StringBuilder result = new StringBuilder(query == null ? "" : query.trim());
        for (String value : attributes.values()) {
            if (value == null || value.isBlank()) continue;
            String clean = value.trim();
            if (!result.toString().toLowerCase(java.util.Locale.ROOT)
                    .contains(clean.toLowerCase(java.util.Locale.ROOT))) {
                result.insert(0, clean + " ");
            }
        }
        return result.toString().trim();
    }

    private static Set<CommerceMode> parseMode(String raw) {
        if (raw == null || raw.isBlank()) return Set.of(CommerceMode.values());
        try {
            return Set.of(CommerceMode.valueOf(raw));
        } catch (IllegalArgumentException ex) {
            return Set.of(CommerceMode.values());
        }
    }
}
