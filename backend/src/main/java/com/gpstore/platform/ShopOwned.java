package com.gpstore.platform;

/**
 * An entity whose rows belong to one shop.
 *
 * IMPLEMENTING THIS IS A DECLARATION, NOT A CONVENIENCE. It says two things
 * that the rest of the tenancy machinery relies on:
 *
 *   1. Reading a row of this type from the wrong shop is a data leak, so the
 *      shop filter restricts every query against it.
 *   2. Writing a row of this type without a shop is a bug, so ShopStamp fills
 *      it in before the insert and refuses when it cannot.
 *
 * WHAT DOES NOT IMPLEMENT IT MATTERS AS MUCH. Products, variants and
 * categories are the shared catalogue: a shop sells a product, it does not own
 * the fact that the product exists. Customers and addresses are the platform's
 * - one account, orders from any shop. Marking either as shop-owned would be
 * the beginning of the model this transformation exists to avoid.
 */
public interface ShopOwned {

    /** The shop this row belongs to, or null before it has been stamped. */
    Long getShopId();

    void setShopId(Long shopId);
}
