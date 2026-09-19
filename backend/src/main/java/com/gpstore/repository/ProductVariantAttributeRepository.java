package com.gpstore.repository;

import com.gpstore.entity.ProductVariantAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * What a variant is made of, in the order the merchant said it.
 *
 * <p>CATALOGUE-LEVEL AND THEREFORE NOT SHOP-FILTERED. How much RAM a phone has
 * is a fact about the phone; who may EDIT that fact is a separate question and
 * is answered by {@code ShopVariantEditing}, not by a tenant filter.
 */
public interface ProductVariantAttributeRepository
        extends JpaRepository<ProductVariantAttribute, Long> {

    List<ProductVariantAttribute> findByProductVariantIdOrderByDisplayOrderAscIdAsc(
            Long productVariantId);

    List<ProductVariantAttribute> findByProductVariantIdInOrderByDisplayOrderAscIdAsc(
            Collection<Long> productVariantIds);

    void deleteByProductVariantId(Long productVariantId);

    /**
     * How many shops list this catalogue variant, ignoring the tenant filter.
     *
     * <p>A NATIVE COUNT ON PURPOSE. The question is explicitly cross-shop -
     * "is anybody else selling this?" - and the Hibernate filter would narrow
     * it to the caller and always answer one. It returns a number and nothing
     * else, so no other shop's commercial data can travel through it.
     *
     * <p>It lives here rather than on the shop repository precisely so it
     * cannot be mistaken for one of that repository's automatically-scoped
     * finders.
     */
    @Query(value = "SELECT count(*) FROM shop_product_variants WHERE product_variant_id = :variantId",
            nativeQuery = true)
    long countShopsListing(@Param("variantId") Long variantId);

    /**
     * How many DISTINCT shops list any variant of this product, ignoring the
     * tenant filter.
     *
     * <p>Same deliberate cross-shop question as {@link #countShopsListing},
     * one level up: it decides whether a merchant may rename or re-categorise
     * the product itself, which is shared with every shop selling it. Returns
     * a number and nothing else.
     */
    @Query(value = "SELECT count(DISTINCT s.shop_id) FROM shop_product_variants s "
            + "JOIN product_variants v ON v.id = s.product_variant_id "
            + "WHERE v.product_id = :productId",
            nativeQuery = true)
    long countShopsListingProduct(@Param("productId") Long productId);
}
