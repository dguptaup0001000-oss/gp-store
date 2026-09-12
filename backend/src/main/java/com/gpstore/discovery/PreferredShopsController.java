package com.gpstore.discovery;

import com.gpstore.platform.CustomerOwnedRead;
import com.gpstore.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The customer's own shop preferences, per category (Part 2 §4).
 *
 * <p>THE CUSTOMER COMES FROM THE TOKEN, never from the path. There is no
 * {customerId} anywhere here, because a customer id a caller could name is
 * a customer id a caller could change into somebody else's shopping habits.
 *
 * <p>READ ACROSS SHOPS, deliberately and by name: a preference list spans
 * every kirana the customer has ever considered, which is exactly the case
 * CustomerOwnedRead exists for (§5 - one account, many shops).
 */
@RestController
@RequestMapping("/api/preferred-shops")
public class PreferredShopsController {

    private final PreferredShops preferred;
    private final CurrentUser currentUser;
    private final CustomerOwnedRead customerOwnedRead;

    public PreferredShopsController(PreferredShops preferred, CurrentUser currentUser,
                                    CustomerOwnedRead customerOwnedRead) {
        this.preferred = preferred;
        this.currentUser = currentUser;
        this.customerOwnedRead = customerOwnedRead;
    }

    /** Everything, by category id. */
    @GetMapping
    public Map<String, Object> mine() {
        Long me = currentUser.customerId();
        Map<Long, List<Long>> byCategory = customerOwnedRead.acrossShops(() -> preferred.all(me));
        return Map.of(
                "maxPerCategory", PreferredShop.MAX_PER_CATEGORY,
                "byCategory", byCategory);
    }

    /** One category's choices, first choice first. */
    @GetMapping("/{categoryId}")
    public Map<String, Object> forCategory(@PathVariable Long categoryId) {
        Long me = currentUser.customerId();
        List<Long> shopIds = customerOwnedRead.acrossShops(
                () -> preferred.forCategory(me, categoryId));
        return Map.of(
                "categoryId", categoryId,
                "maxPerCategory", PreferredShop.MAX_PER_CATEGORY,
                "shopIds", shopIds);
    }

    /**
     * Replaces a category's choices.
     *
     * <p>An empty list clears the category - that is how "actually, no
     * preference" is said, and it needs to be sayable.
     */
    @PutMapping("/{categoryId}")
    public Map<String, Object> setForCategory(@PathVariable Long categoryId,
                                              @RequestBody Map<String, Object> request) {
        Long me = currentUser.customerId();
        List<Long> shopIds = readShopIds(request.get("shopIds"));
        customerOwnedRead.acrossShops(() -> preferred.setForCategory(me, categoryId, shopIds));
        return forCategory(categoryId);
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> clear(@PathVariable Long categoryId) {
        Long me = currentUser.customerId();
        customerOwnedRead.acrossShops(() -> {
            preferred.clearCategory(me, categoryId);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    private static List<Long> readShopIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Iterable<?> items)) {
            throw new com.gpstore.exception.BadRequestException("shopIds must be a list.");
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            try {
                ids.add(Long.valueOf(String.valueOf(item).trim()));
            } catch (NumberFormatException notANumber) {
                throw new com.gpstore.exception.BadRequestException(
                        "shopIds must be numbers - received '" + item + "'.");
            }
        }
        return ids;
    }
}
