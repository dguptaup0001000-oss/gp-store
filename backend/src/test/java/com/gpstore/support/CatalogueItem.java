package com.gpstore.support;

import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

/**
 * ONE CATALOGUE ITEM A TEST OWNS, INSTEAD OF ONE IT BORROWS.
 *
 * <p>THE BUG THIS EXISTS TO KILL. A test that needs something to sell reaches
 * for the catalogue like this:
 *
 * <pre>SELECT id FROM product_variants ORDER BY id LIMIT 1</pre>
 *
 * <p>That is not a fixture, it is a wish. {@code CatalogSeedService} is only
 * reachable from {@code POST /api/admin/catalog/seed} - nothing calls it at
 * startup - so on a database that has just been wiped and migrated, {@code
 * product_variants} is EMPTY until some other test happens to seed it. Whether
 * the row exists therefore depends on which test ran first.
 *
 * <p>On a developer's database, which has accumulated thousands of variants
 * over months, it always exists and the query always works. On CI, where the
 * schema is dropped and rebuilt every run, it depends on the order surefire
 * chose that day. That is exactly how {@code InventoryUnderConcurrencyTest}
 * came to fail five tests on CI with {@code Incorrect result size: expected 1,
 * actual 0} while passing locally every time.
 *
 * <p>So: create the row, use it, delete it. A test that owns its data does not
 * care what ran before it.
 */
public final class CatalogueItem {

    private final JdbcTemplate jdbc;
    private final Long categoryId;
    private final Long productId;
    private final Long variantId;

    private CatalogueItem(JdbcTemplate jdbc, Long categoryId, Long productId, Long variantId) {
        this.jdbc = jdbc;
        this.categoryId = categoryId;
        this.productId = productId;
        this.variantId = variantId;
    }

    /** The variant id to sell, stock, list or price. */
    public Long variantId() {
        return variantId;
    }

    public Long productId() {
        return productId;
    }

    /**
     * Creates a category, a product and one variant, all named after {@code tag}.
     *
     * <p>WRITTEN THROUGH THE REPOSITORIES, not with hand-built INSERTs. A
     * fixture made of literal SQL has to be re-taught the schema every time a
     * column gains a NOT NULL - which has already happened once in this suite,
     * to {@code merchants.active}. The entities know their own shape.
     *
     * <p>The catalogue is central rather than shop-owned, so the writes happen
     * in {@link TenantScope#platform()}; a shop-scoped write would stamp a
     * shop onto rows every shop is supposed to share.
     */
    public static CatalogueItem create(String tag,
                                       JdbcTemplate jdbc,
                                       CategoryRepository categories,
                                       ProductRepository products,
                                       ProductVariantRepository variants) {
        return TenantContext.runWithin(TenantScope.platform(), () -> {
            Category category = new Category();
            category.setName("Fixture category " + tag);
            category.setActive(true);
            category.setGstRate(new BigDecimal("5"));
            Category savedCategory = categories.save(category);

            Product product = new Product();
            product.setName("Fixture item " + tag);
            product.setCategory(savedCategory);
            product.setActive(true);
            Product savedProduct = products.save(product);

            ProductVariant variant = new ProductVariant();
            variant.setProduct(savedProduct);
            variant.setQuantity(1.0);
            variant.setUnit("kg");
            variant.setSellingPrice(new BigDecimal("100.00"));
            variant.setAvailable(Boolean.TRUE);
            variant.setActive(Boolean.TRUE);
            ProductVariant savedVariant = variants.save(variant);

            return new CatalogueItem(jdbc, savedCategory.getId(),
                    savedProduct.getId(), savedVariant.getId());
        });
    }

    /**
     * Removes exactly the three rows this created, and whatever hangs off them.
     *
     * <p>KEYED ON ITS OWN IDS. Deleting by anything broader - a shop, a name
     * pattern, a whole table - takes out rows the test never created, which is
     * its own class of damage and has happened here before.
     */
    public void remove() {
        jdbc.update("DELETE FROM inventory WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id = ?", variantId);
        jdbc.update("DELETE FROM product_variants WHERE id = ?", variantId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
    }
}
