package com.gpstore.platform.shopinfo;

/** The promises §3 says a shop makes. Closed set; the column is a string only so a new one is a row. */
public enum ShopPolicyKind {

    /** How and when this shop delivers, in its own words. */
    DELIVERY,

    /** When a customer may cancel, and what happens if they do. */
    CANCELLATION,

    /** What this shop takes back, and how the money comes home. */
    RETURNS
}
