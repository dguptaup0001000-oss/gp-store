package com.gpstore.catalog.shop;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A shop's own departments: create, rename, retire.
 *
 * <p>WHAT THIS IS NOT. It is not a way into {@code categories}, the platform
 * taxonomy every merchant reads. Nothing here writes that table. A merchant
 * creating "charger" gets a row belonging to their shop and to nobody else;
 * GUPT SAREE does not find "charger" on its screen the next morning, and no
 * customer's category tree grows a new entry.
 *
 * <p>WHY IT EXISTS. The merchant admin app has an Add Category button. Before
 * this, that button posted to the platform tree, which needs CATALOG_DEFINE the
 * moment a second shop is trading - so it always answered 403 and the app said
 * "You don't have permission to do that". A button that cannot succeed is worse
 * than no button: the merchant cannot tell a bug from a rule. Either the
 * operation is theirs or the button should not be there, and organising your
 * own shelf is plainly theirs.
 */
@Service
public class ShopCategoryService {

    private final ShopCategoryRepository shopCategories;
    private final CategoryRepository globalCategories;
    private final ShopProductVariantRepository listings;

    public ShopCategoryService(ShopCategoryRepository shopCategories,
                               CategoryRepository globalCategories,
                               ShopProductVariantRepository listings) {
        this.shopCategories = shopCategories;
        this.globalCategories = globalCategories;
        this.listings = listings;
    }

    public record ShopCategoryRequest(String name, String description, Long globalCategoryId,
                                      Integer displayOrder, Boolean active) {
    }

    public record ShopCategoryView(Long id, String name, String description,
                                   Long globalCategoryId, Integer displayOrder, Boolean active) {
        static ShopCategoryView of(ShopCategory row) {
            return new ShopCategoryView(row.getId(), row.getName(), row.getDescription(),
                    row.getGlobalCategoryId(), row.getDisplayOrder(), row.getActive());
        }
    }

    @Transactional(readOnly = true)
    public List<ShopCategoryView> mine() {
        return shopCategories.findAllByOrderByDisplayOrderAscNameAsc()
                .stream().map(ShopCategoryView::of).toList();
    }

    @Transactional
    public ShopCategoryView create(ShopCategoryRequest request) {
        String name = requireName(request.name());
        // ASKED BEFORE INSERTING so a double tap gets a sentence rather than a
        // raw constraint violation. The unique index still backs the same rule
        // for the race this check cannot win - both exist on purpose.
        shopCategories.findByNameIgnoringCase(name).ifPresent(existing -> {
            throw new ConflictException("This shop already has a department called \"" + name + "\".");
        });

        ShopCategory row = new ShopCategory();
        row.setName(name);
        row.setDescription(trimToNull(request.description()));
        row.setGlobalCategoryId(resolveGlobal(request.globalCategoryId()));
        row.setDisplayOrder(request.displayOrder());
        row.setActive(request.active() == null ? Boolean.TRUE : request.active());
        // shop_id is NOT set here and must not be: TenantEntityListener stamps
        // it from the resolved scope on insert, so a shop id arriving in a
        // request body can never decide which shop gets the row.
        return ShopCategoryView.of(shopCategories.save(row));
    }

    @Transactional
    public ShopCategoryView update(Long id, ShopCategoryRequest request) {
        ShopCategory row = mine(id);
        if (request.name() != null) {
            String name = requireName(request.name());
            shopCategories.findByNameIgnoringCase(name).ifPresent(other -> {
                if (!other.getId().equals(row.getId())) {
                    throw new ConflictException(
                            "This shop already has a department called \"" + name + "\".");
                }
            });
            row.setName(name);
        }
        if (request.description() != null) {
            row.setDescription(trimToNull(request.description()));
        }
        if (request.globalCategoryId() != null) {
            row.setGlobalCategoryId(resolveGlobal(request.globalCategoryId()));
        }
        if (request.displayOrder() != null) {
            row.setDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            row.setActive(request.active());
        }
        return ShopCategoryView.of(shopCategories.save(row));
    }

    /**
     * Retires a department.
     *
     * <p>DEACTIVATED, NOT DELETED, while listings still point at it. Removing
     * the row would orphan whatever the merchant had filed under it, and a
     * merchant tidying their menu is not asking for their stock to lose its
     * shelf. With nothing pointing at it the row goes for real.
     */
    @Transactional
    public void remove(Long id) {
        ShopCategory row = mine(id);
        long filed = listings.countByShopCategoryId(id);
        if (filed > 0) {
            row.setActive(Boolean.FALSE);
            shopCategories.save(row);
            return;
        }
        shopCategories.delete(row);
    }

    /**
     * Read through the shop-scoped repository, so "not mine" and "not there"
     * are the same answer and neither leaks another shop's ids.
     */
    private ShopCategory mine(Long id) {
        return shopCategories.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("This shop has no such department."));
    }

    private Long resolveGlobal(Long globalCategoryId) {
        if (globalCategoryId == null) {
            return null;
        }
        globalCategories.findById(globalCategoryId).orElseThrow(
                () -> new BadRequestException("That platform category does not exist."));
        return globalCategoryId;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new BadRequestException("A department needs a name.");
        }
        if (name.length() > 120) {
            throw new BadRequestException("That department name is too long.");
        }
        return name;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
