package com.gpstore.discovery;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * "My shops", one answer per category (Part 2 §4).
 *
 * <p>WHAT A PREFERENCE IS: an ordering. Up to two shops per category, first
 * choice and second, used to sort the "My Preferred Shops" view.
 *
 * <p>WHAT A PREFERENCE IS NOT, and §4 says all four out loud: it does not
 * hide other shops, does not stop the customer comparing, does not stop them
 * switching, and does not stop them buying elsewhere. There is no method here
 * that filters anything out, and there is no flag anywhere that a browse
 * query consults. That absence is the feature, and
 * {@code MyPreferredShopsTest} asserts it by checking that the other shops
 * are still in the list.
 *
 * <p>AND IT IS NOT OVERRIDDEN BY A CHEAPER SHOP. §4: "do not silently
 * override the customer's explicit preference simply because another shop is
 * cheaper". Best Deal is a mode the customer chooses, not a correction the
 * platform applies to a mode they chose instead.
 */
@Service
public class PreferredShops {

    private final PreferredShopRepository preferences;
    private final CategoryRepository categories;
    private final ShopDiscovery discovery;

    public PreferredShops(PreferredShopRepository preferences,
                          CategoryRepository categories,
                          ShopDiscovery discovery) {
        this.preferences = preferences;
        this.categories = categories;
        this.discovery = discovery;
    }

    /** One category's choices, first choice first. */
    @Transactional(readOnly = true)
    public List<Long> forCategory(Long customerId, Long categoryId) {
        if (customerId == null || categoryId == null) {
            return List.of();
        }
        return preferences.findByCustomerIdAndCategoryIdOrderBySlotAsc(customerId, categoryId)
                .stream().map(PreferredShop::getPreferredShopId).toList();
    }

    /** Everything this customer has chosen, by category. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> all(Long customerId) {
        Map<Long, List<Long>> byCategory = new LinkedHashMap<>();
        if (customerId == null) {
            return byCategory;
        }
        for (PreferredShop row : preferences.findByCustomerIdOrderByCategoryIdAscSlotAsc(customerId)) {
            byCategory.computeIfAbsent(row.getCategoryId(), c -> new ArrayList<>())
                    .add(row.getPreferredShopId());
        }
        return byCategory;
    }

    /**
     * Replaces this category's choices wholesale.
     *
     * <p>WHOLESALE, NOT ADD-ONE. "Set my kirana shops to A and B" is what a
     * customer means when they press save on a two-slot picker, and an
     * add/remove pair would let the two get out of step - a removal that
     * failed leaving three rows where the screen showed two. Sending an empty
     * list clears the category, which is how "actually, no preference" is
     * said.
     *
     * @param shopIds up to two, in the customer's own order of preference
     */
    @Transactional
    public List<Long> setForCategory(Long customerId, Long categoryId, List<Long> shopIds) {
        if (customerId == null) {
            throw new BadRequestException("No customer.");
        }
        if (categoryId == null || !categories.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category not found");
        }

        List<Long> wanted = new ArrayList<>(new LinkedHashSet<>(
                shopIds == null ? List.of() : shopIds));

        if (wanted.size() > PreferredShop.MAX_PER_CATEGORY) {
            throw new BadRequestException(
                    "You can pick at most " + PreferredShop.MAX_PER_CATEGORY
                            + " preferred shops for a category.");
        }

        for (Long shopId : wanted) {
            // BROWSABLE, NOT MERELY EXISTING. Preferring a suspended or draft
            // shop would put a storefront the customer cannot open at the top
            // of their own list, and they would have no way to tell why.
            if (shopId == null || !discovery.isBrowsableByCustomers(shopId)) {
                throw new BadRequestException("That shop is not open to customers.");
            }
        }

        preferences.deleteForCategory(customerId, categoryId);
        // Flush the delete before the inserts, or the unique index on
        // (customer, category, slot) rejects the new slot 1 against the old
        // one Hibernate has not written out yet.
        preferences.flush();

        List<PreferredShop> rows = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            rows.add(PreferredShop.of(customerId, categoryId, wanted.get(i), i + 1));
        }
        preferences.saveAll(rows);
        return List.copyOf(wanted);
    }

    /** Clears a category. The customer has no preference there any more. */
    @Transactional
    public void clearCategory(Long customerId, Long categoryId) {
        if (customerId == null || categoryId == null) {
            return;
        }
        preferences.deleteForCategory(customerId, categoryId);
    }

    /**
     * Orders a list of shops by this customer's preference for a category.
     *
     * <p>ORDERS, NEVER FILTERS. Every shop handed in comes back out - §4 is
     * explicit that the customer must still see, compare, switch and buy
     * elsewhere. A preferred shop that is closed today still sorts first,
     * because the customer's answer to "where do I shop" does not change with
     * the hour; whether it can take an order today is a different field they
     * can already read.
     */
    @Transactional(readOnly = true)
    public <T> List<T> preferredFirst(Long customerId, Long categoryId,
                                      List<T> shops, java.util.function.Function<T, Long> shopIdOf) {
        List<Long> preferred = forCategory(customerId, categoryId);
        if (preferred.isEmpty() || shops == null || shops.isEmpty()) {
            return shops == null ? List.of() : List.copyOf(shops);
        }
        List<T> ordered = new ArrayList<>(shops);
        ordered.sort(java.util.Comparator.comparingInt(shop -> {
            int at = preferred.indexOf(shopIdOf.apply(shop));
            // Not preferred sorts after both slots, and keeps whatever order
            // the caller had - distance, usually.
            return at < 0 ? Integer.MAX_VALUE : at;
        }));
        return List.copyOf(ordered);
    }
}
