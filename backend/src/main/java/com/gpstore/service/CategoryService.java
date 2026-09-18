package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.upload.CatalogImageCleanup;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CatalogImageCleanup imageCleanup;
    private final com.gpstore.discovery.ShopCategoryPresence shelves;

    public CategoryService(CategoryRepository categoryRepository,
                           ProductRepository productRepository,
                           CatalogImageCleanup imageCleanup,
                           com.gpstore.discovery.ShopCategoryPresence shelves) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.imageCleanup = imageCleanup;
        this.shelves = shelves;
    }

    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public Category saveCategory(Category category) {
        if (category.getActive() == null) {
            category.setActive(true);
        }
        applyImageUrlIfPresent(category, category.getImageUrl(), true);
        return categoryRepository.save(category);
    }

    /**
     * Storefront (and the admin picker that shares this URL): active
     * categories only, newest-name order, hard cap.
     *
     * findAll() used to serialise every row, including deactivated ones and
     * leftover test fixtures. A polluted database with thousands of
     * categories turned GET /api/categories into a multi-megabyte payload
     * on every home-screen open - the same shape as the 201 GB load-test
     * transfer, without any 5,000-VU story. A real shop has a few dozen
     * departments, not thousands.
     */
    private static final int STOREFRONT_CATEGORY_CAP = 100;

    @Cacheable(value = "categories", sync = true)
    public List<Category> getAllCategories() {
        return categoryRepository.findByActiveTrueOrderByNameAsc(
                org.springframework.data.domain.PageRequest.of(0, STOREFRONT_CATEGORY_CAP));
    }

    /**
     * The categories THIS shop actually trades in.
     *
     * <p>WHY A NEWLY ONBOARDED PHONE SHOP WAS SHOWN "Atta, Rice & Dal". The
     * merchant's Categories screen read {@link #getAllCategories()}, which is
     * the platform's whole taxonomy - every department every merchant on
     * GP-STORE sells, descriptions and all ("for everyday kirana needs"). For
     * the one shop that existed when this was written, the taxonomy and that
     * shop's departments were the same list. With several merchants trading
     * they are not, and a phone shop was handed a kirana's shelf plan to
     * manage.
     *
     * <p>DERIVED FROM THE SHELF, NOT A NEW TABLE. A shop trades in a category
     * when it lists something orderable in it - which
     * {@code shop_product_variants} already records, and which
     * {@link com.gpstore.discovery.ShopCategoryPresence} already reads for the
     * customer's discovery screens. Giving merchants a declared list instead
     * would be a second, staler answer to a question the data already answers:
     * a merchant who stops stocking phone cases would keep "Cases & Covers"
     * forever.
     *
     * <p>EMPTY IS A REAL ANSWER. A shop that has listed nothing yet trades in
     * nothing yet, and gets an empty list rather than somebody else's
     * departments. The Add Product screen still offers the full taxonomy to
     * choose from - see {@link #getAllCategories()} - which is how the first
     * category gets onto the shelf.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Category> getCategoriesOnMyShelf() {
        com.gpstore.platform.TenantScope scope = com.gpstore.platform.TenantContext.current();
        if (scope == null || scope.isPlatform()) {
            // No shop in scope: the platform console is asking, and the whole
            // taxonomy IS its subject.
            return getAllCategories();
        }
        java.util.Set<Long> mine = shelves.categoriesSoldByAnyOf(List.of(scope.requireShopId()));
        if (mine.isEmpty()) {
            return List.of();
        }
        return categoryRepository.findByActiveTrueOrderByNameAsc(
                        org.springframework.data.domain.PageRequest.of(0, STOREFRONT_CATEGORY_CAP))
                .stream()
                .filter(category -> mine.contains(category.getId()))
                .toList();
    }

    public Category getById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public Category update(Long id, Category updated) {
        Category existing = getById(id);
        String previousImage = existing.getImageUrl();

        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        // Null means the client omitted imageUrl (Flutter's updateCategory
        // does). Copying that null used to wipe a category's photo on every
        // rename. Empty string still means "clear it".
        applyImageUrlIfPresent(existing, updated.getImageUrl(), false);
        existing.setGstRate(updated.getGstRate());
        existing.setActive(updated.getActive());

        Category saved = categoryRepository.save(existing);
        if (updated.getImageUrl() != null) {
            imageCleanup.deleteReplacedAfterCommit(previousImage, saved.getImageUrl());
        }
        return saved;
    }

    /**
     * Soft-delete only (active=false) - a hard delete would either orphan every
     * product still pointing at this category, or fail on the FK constraint.
     * Products keep their category reference; the storefront just needs to
     * stop showing a deactivated category.
     */
    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public void deactivate(Long id) {
        Category category = getById(id);
        category.setActive(false);
        categoryRepository.save(category);
    }

    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public void hardDelete(Long id) {
        if (productRepository.existsByCategoryId(id)) {
            throw new ConflictException(
                    "Cannot delete a category that still has products - deactivate it instead, or move the products first");
        }
        categoryRepository.deleteById(id);
    }

    private static void applyImageUrlIfPresent(Category category, String imageUrl, boolean always) {
        if (!always && imageUrl == null) {
            return;
        }
        com.gpstore.catalog.CatalogUrlValidator.requireAllowedImageUrlOrEmpty(imageUrl);
        category.setImageUrl(com.gpstore.catalog.CatalogUrlValidator.trimToNull(imageUrl));
    }
}
