package com.gpstore.catalog.shop;

import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

/**
 * "Is this on the shelf of the shop the customer is standing in?"
 *
 * ONE ANSWER, ONE PLACE. Products and variants are central by design (§10):
 * one row per item for the whole marketplace, and what makes a shop's
 * catalogue its own is which of them it LISTS, at what price. Every browse
 * surface therefore has to ask the same extra question, and asking it in nine
 * different queries is how eight of them end up phrased slightly differently
 * and one of them ends up wrong.
 *
 * WHY THE SHOP ID IS PASSED EXPLICITLY. Hibernate's @Filter narrows
 * shop_product_variants automatically, but only for JPQL and entity loads.
 * The browse, search and bestseller queries are NATIVE SQL - the filter does
 * not reach them, which is the blind spot Slice 8 named. So the id is bound
 * as a parameter, and it comes from TenantContext (the credential) rather
 * than from anything a caller sent.
 *
 * INERT UNDER SINGLE_SHOP, deliberately and by construction: {@link #applies}
 * is false, every predicate collapses to {@code true}, and no parameter is
 * bound. A deployment with one shop runs the queries it has always run,
 * including for a variant that was priced without ever being listed - real
 * data whose disappearance would be a live product vanishing from a working
 * shop (§12).
 */
@Component
public class Storefront {

    private final PlatformProperties platform;

    public Storefront(PlatformProperties platform) {
        this.platform = platform;
    }

    /** The parameter name every predicate below binds. */
    public static final String SHOP_PARAM = "storefrontShopId";

    /**
     * Whether browsing has to be narrowed to one shop's shelf at all.
     *
     * Both halves matter. A single-shop deployment has no second shelf to be
     * confused with; and a caller with no shop in scope - the platform admin,
     * a background job - is not standing in a storefront, so narrowing to
     * "their" shop would be narrowing to nothing.
     */
    public boolean applies() {
        if (!platform.getMode().isMultiShop()) {
            return false;
        }
        TenantScope scope = TenantContext.current();
        return scope != null && !scope.isPlatform() && scope.shopId() != null;
    }

    /** The shop in scope, or null when {@link #applies()} is false. */
    public Long shopId() {
        TenantScope scope = TenantContext.current();
        return scope == null || scope.isPlatform() ? null : scope.shopId();
    }

    /**
     * SQL for "this shop lists that variant, at a price it can charge".
     *
     * @param variantAlias the alias of a product_variants row in the caller's query
     */
    public String variantIsOnTheShelf(String variantAlias) {
        if (!applies()) {
            return "true";
        }
        return """
                EXISTS (SELECT 1 FROM shop_product_variants shelf
                        WHERE shelf.product_variant_id = %s.id
                          AND shelf.shop_id = :%s
                          AND shelf.available = true
                          AND (shelf.active IS NULL OR shelf.active = true)
                          AND shelf.selling_price IS NOT NULL
                          AND shelf.selling_price > 0)
                """.formatted(variantAlias, SHOP_PARAM);
    }

    /**
     * SQL for "this shop sells at least one variant of that product".
     *
     * @param productAlias the alias of a products row in the caller's query
     */
    public String productIsOnTheShelf(String productAlias) {
        if (!applies()) {
            return "true";
        }
        return """
                EXISTS (SELECT 1 FROM product_variants shelfv
                        JOIN shop_product_variants shelf
                          ON shelf.product_variant_id = shelfv.id
                        WHERE shelfv.product_id = %s.id
                          AND shelfv.available = true
                          AND (shelfv.active IS NULL OR shelfv.active = true)
                          AND shelf.shop_id = :%s
                          AND shelf.available = true
                          AND (shelf.active IS NULL OR shelf.active = true)
                          AND shelf.selling_price IS NOT NULL
                          AND shelf.selling_price > 0)
                """.formatted(productAlias, SHOP_PARAM);
    }

    /**
     * Binds the shop id, if the predicates above used it.
     *
     * Binding a parameter a query does not mention is an error in JPA, so this
     * has to make the same decision {@link #applies()} made - which is why it
     * reads the same method rather than a copy of the condition.
     */
    public void bind(Query query) {
        if (applies()) {
            query.setParameter(SHOP_PARAM, shopId());
        }
    }
}
