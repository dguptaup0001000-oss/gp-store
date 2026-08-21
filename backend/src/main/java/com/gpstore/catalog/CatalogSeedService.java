package com.gpstore.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.Category;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the generated test catalog into the shop's real tables.
 *
 * SAFE TO RUN AS MANY TIMES AS YOU LIKE. Everything keys on the variant SKU
 * (GP-000001...), which the generator assigns deterministically. A second run
 * finds every SKU already present and updates it in place; it never inserts a
 * second copy. That is enforced twice over - by the lookup here, and by the
 * unique index on product_variants.sku added in V15, which would reject a
 * duplicate even if this code were wrong.
 *
 * WHAT IT WILL NEVER TOUCH, and this is the important part:
 *
 *   - Any product it did not create. Updates are applied only to rows whose
 *     variant carries a GP- SKU from this file. A product the shop added by
 *     hand has a null or different SKU and is invisible to this service.
 *   - Orders, customers, carts, addresses, payments. Nothing here reads or
 *     writes any of them.
 *   - Stock of a product that has been ordered. See the stock rule below.
 *
 * THE STOCK RULE IS THE SUBTLE ONE. On first insert the seeded stock level is
 * written as-is. On a re-run it is NOT reset, because doing so would silently
 * undo real inventory movement: someone testing the app places an order, the
 * stock drops to 7, the seeder runs again on the next deploy and puts it back
 * to 50 - and the order that consumed it is now unaccounted for. Re-running
 * refreshes the catalogue, not the warehouse.
 */
@Service
public class CatalogSeedService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedService.class);

    private static final String CATALOG_PATH = "catalog/gp-store-test-catalog.json";

    /**
     * Batched rather than one enormous transaction. A thousand products is
     * roughly three thousand rows across three tables; holding all of that in
     * one transaction on a 0.5 vCPU instance with a ten-connection pool means
     * one long-running writer and a bloated undo log. Batching also means a
     * failure halfway through leaves a partially-seeded catalogue that the
     * next run simply completes, rather than rolling back forty seconds of
     * work.
     */
    private static final int BATCH_SIZE = 100;

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryRepository inventoryRepository;
    private final ObjectMapper objectMapper;
    private final CatalogSeedService self;

    public CatalogSeedService(ProductRepository productRepository,
                              ProductVariantRepository variantRepository,
                              CategoryRepository categoryRepository,
                              InventoryRepository inventoryRepository,
                              ObjectMapper objectMapper,
                              @org.springframework.context.annotation.Lazy CatalogSeedService self) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.categoryRepository = categoryRepository;
        this.inventoryRepository = inventoryRepository;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    public record SeedResult(int total, int inserted, int updated, int skipped,
                             int categoriesCreated, List<String> problems) {}

    /**
     * NOT @Transactional - it drives the batches, each of which is its own
     * transaction (self-invocation through the proxy, which is what the
     * @Lazy self-injection above is for).
     */
    public SeedResult seed() {
        List<CatalogSeedRecord> records = load();
        log.info("Catalog seed starting: {} records", records.size());

        Map<String, Long> categoryIds = self.ensureCategories(records);
        int categoriesCreated = categoryIds.size();

        int inserted = 0, updated = 0, skipped = 0;
        List<String> problems = new java.util.ArrayList<>();

        for (int from = 0; from < records.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, records.size());
            try {
                int[] counts = self.seedBatch(records.subList(from, to), categoryIds, problems);
                inserted += counts[0];
                updated += counts[1];
            } catch (RuntimeException e) {
                // One bad batch must not lose the other nine. Recorded and
                // reported rather than swallowed - a silent partial seed is
                // exactly the thing that gets discovered on a customer's
                // screen three weeks later.
                skipped += (to - from);
                problems.add("batch " + from + "-" + to + " failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("Catalog seed batch {}-{} failed", from, to, e);
            }
        }

        log.info("Catalog seed finished: {} inserted, {} updated, {} skipped",
                inserted, updated, skipped);
        return new SeedResult(records.size(), inserted, updated, skipped, categoriesCreated, problems);
    }

    /**
     * Categories are matched BY NAME, case-insensitively, and created only if
     * genuinely absent.
     *
     * Name is the only stable identity available - the file cannot know the
     * database's generated ids, and hardcoding them would break the moment
     * this runs against a second environment. Case-insensitive because
     * "Dairy & Eggs" and "Dairy & eggs" are the same aisle to a shopper, and
     * creating both is precisely the duplicate the brief rules out.
     */
    @Transactional
    public Map<String, Long> ensureCategories(List<CatalogSeedRecord> records) {
        Map<String, Long> byLowerName = new HashMap<>();
        for (Category existing : categoryRepository.findAll()) {
            if (existing.getName() != null) {
                byLowerName.putIfAbsent(existing.getName().trim().toLowerCase(), existing.getId());
            }
        }

        Map<String, Long> resolved = new HashMap<>();
        for (CatalogSeedRecord record : records) {
            String name = record.category() == null ? null : record.category().trim();
            if (name == null || name.isEmpty() || resolved.containsKey(name)) {
                continue;
            }
            Long id = byLowerName.get(name.toLowerCase());
            if (id == null) {
                Category category = new Category();
                category.setName(name);
                category.setDescription(name + " - everyday kirana essentials.");
                category.setActive(true);
                id = categoryRepository.save(category).getId();
                byLowerName.put(name.toLowerCase(), id);
                log.info("Catalog seed created category '{}' (id {})", name, id);
            }
            resolved.put(name, id);
        }
        return resolved;
    }

    @Transactional
    public int[] seedBatch(List<CatalogSeedRecord> batch,
                           Map<String, Long> categoryIds,
                           List<String> problems) {
        int inserted = 0, updated = 0;

        for (CatalogSeedRecord record : batch) {
            if (record.sku() == null || record.sku().isBlank()) {
                problems.add("record without SKU skipped: " + record.name());
                continue;
            }

            Optional<ProductVariant> existing = variantRepository.findBySku(record.sku());
            if (existing.isPresent()) {
                applyToExisting(existing.get(), record, categoryIds);
                updated++;
            } else {
                insertNew(record, categoryIds);
                inserted++;
            }
        }
        return new int[]{inserted, updated};
    }

    private void insertNew(CatalogSeedRecord record, Map<String, Long> categoryIds) {
        Product product = new Product();
        applyProductFields(product, record, categoryIds);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        applyVariantFields(variant, record);
        variant = variantRepository.save(variant);

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(record.stock() == null ? 0 : record.stock());
        inventory.setReservedStock(0);
        inventory.setMinimumStock(5);
        inventoryRepository.save(inventory);
    }

    private void applyToExisting(ProductVariant variant, CatalogSeedRecord record,
                                 Map<String, Long> categoryIds) {
        Product product = variant.getProduct();
        if (product != null) {
            applyProductFields(product, record, categoryIds);
            productRepository.save(product);
        }
        applyVariantFields(variant, record);
        variantRepository.save(variant);
        // Inventory deliberately untouched on update - see the class comment
        // on why re-running must not reset stock.
    }

    private void applyProductFields(Product product, CatalogSeedRecord record,
                                    Map<String, Long> categoryIds) {
        product.setName(record.name());
        product.setBrand(normaliseBrand(record.brand()));
        product.setDescription(record.description());
        product.setSubcategory(record.subcategory());
        product.setSearchKeywords(joinKeywords(record.searchKeywords()));
        product.setActive(record.active() == null || record.active());
        product.setBestseller(Boolean.TRUE.equals(record.bestseller()));
        product.setFeatured(Boolean.TRUE.equals(record.featured()));
        product.setIsTestData(Boolean.TRUE.equals(record.isTestData()));
        product.setPriceVerified(Boolean.TRUE.equals(record.priceVerified()));
        product.setDataSource(record.dataSource());
        product.setUpdatedAt(LocalDateTime.now());

        Long categoryId = categoryIds.get(record.category());
        if (categoryId != null) {
            Category ref = new Category();
            ref.setId(categoryId);
            product.setCategory(ref);
        }
        // imageSource is NOT set here. Images are the backfill job's business,
        // and stamping a source before any image exists would be a lie the
        // pre-launch audit then has to unpick.
    }

    private void applyVariantFields(ProductVariant variant, CatalogSeedRecord record) {
        variant.setSku(record.sku());
        variant.setQuantity(record.packQuantity());
        variant.setUnit(record.packUnit());
        variant.setMrp(record.mrp());
        variant.setSellingPrice(record.sellingPrice());
        variant.setAvailable(record.available() == null || record.available());
        variant.setActive(true);
        variant.setDisplayOrder(0);
        // image_url on the variant is the LIST thumbnail. Left alone here for
        // the same reason as above: the backfill owns every image field, and
        // it only ever writes a URL it has confirmed resolves.
    }

    /**
     * Brand normalisation, which is the whole of section 13's "do not create
     * Amul, AMUL and amul".
     *
     * Brand is a plain string column on products - there is no brand table -
     * so "Shop by Brand" groups by exactly this value and any inconsistency
     * shows up as two brand tiles for one brand. Trimming and collapsing
     * internal whitespace is all that is needed given the generator emits a
     * single canonical spelling per brand; the guard is here because this
     * method is also the right place for it if the file is ever hand-edited.
     */
    private String normaliseBrand(String brand) {
        return brand == null ? null : brand.trim().replaceAll("\\s+", " ");
    }

    private String joinKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        String joined = String.join(" ", keywords);
        return joined.length() <= 500 ? joined : joined.substring(0, 500);
    }

    List<CatalogSeedRecord> load() {
        try (InputStream in = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            CatalogSeedFile file = objectMapper.readValue(in, CatalogSeedFile.class);
            return file.products() == null ? List.of() : file.products();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + CATALOG_PATH, e);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogSeedFile(int version, String note, List<CatalogSeedRecord> products) {}
}
