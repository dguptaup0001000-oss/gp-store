package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

import jakarta.persistence.*;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String brand;

    /**
     * URL of an optional GLB/GLTF model, shown only if the customer asks for
     * it on the detail screen. Null for almost every product, and that is
     * the expected state rather than a gap to be backfilled - see V13.
     */
    @Column(name = "model_3d_url", length = 500)
    private String model3dUrl;

    // Flipped to LAZY - ProductController now returns ProductResponse (see
        // com.gpstore.dto.response.ProductResponse) from every endpoint, and
        // the methods that convert to it are @Transactional(readOnly = true).
        // This was the last of the 13 deliberately-EAGER relations from the
        // audit - the whole 13-relation EAGER->LAZY audit is now complete.
        @ManyToOne(fetch = FetchType.LAZY)
            private Category category;

  @OneToMany(mappedBy = "product")
@JsonManagedReference
private List<ProductVariant> variants;

    private Boolean active;

    // ---------------------------------------------------------------
    // Catalog metadata (V15). All nullable/defaulted - see the migration
    // on why nothing here may be NOT NULL without a default.
    // ---------------------------------------------------------------

    @Column(length = 1000)
    private String description;

    /**
     * Grouping WITHIN a category, as a column rather than a second category
     * row: "Atta, Rice & Dal" > "Atta". categories is flat and the home
     * screen lists it directly, so child rows there would put "Atta" on the
     * home screen beside its own parent.
     */
    @Column(length = 100)
    private String subcategory;

    /** Space-separated terms the customer might type. Feeds search only. */
    @Column(name = "search_keywords", length = 500)
    private String searchKeywords;

    private Boolean bestseller = Boolean.FALSE;

    private Boolean featured = Boolean.FALSE;

    /**
     * Seeded test data, and the flag the pre-launch cleanup keys on.
     *
     * Defaults FALSE in the database precisely so that anything a human adds
     * through the admin screens is never swept up by that cleanup. A product
     * has to be deliberately marked to be deletable by it.
     */
    @Column(name = "is_test_data")
    private Boolean isTestData = Boolean.FALSE;

    /**
     * Separate from isTestData on purpose: someone can confirm a product is
     * genuinely stocked long before anyone checks its price against a shelf.
     * Collapsing the two would let a half-checked product read as verified.
     */
    @Column(name = "price_verified")
    private Boolean priceVerified = Boolean.FALSE;

    @Column(name = "data_source", length = 60)
    private String dataSource;

    /** Where the gallery images came from, e.g. "openfoodfacts". */
    @Column(name = "image_source", length = 60)
    private String imageSource;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    // Auto-set on creation (@PrePersist below) - never client-supplied.
    // This is what "New Arrivals" actually sorts by.
    private java.time.LocalDateTime createdAt;

    /**
     * THESE FOUR FLAGS MAY NEVER BE NULL, and defaulting them in Java is the
     * only thing that actually enforces it.
     *
     * V15 created bestseller, featured, is_test_data and price_verified as
     * NOT NULL DEFAULT FALSE. A column default only applies when the INSERT
     * OMITS the column - and Hibernate never omits a mapped column. It lists
     * every one of them and binds NULL for anything unset, so the default
     * never gets a chance and Postgres refuses the row:
     *
     *     null value in column "bestseller" of relation "products"
     *     violates not-null constraint
     *
     * That is what the admin Add Product screen was hitting. The form sends
     * name, brand, category and active; nothing sends these four; Hibernate
     * bound four NULLs.
     *
     * WHY IT NEVER REPRODUCED LOCALLY, which is the part worth remembering:
     * V15 uses ADD COLUMN IF NOT EXISTS. On a machine where Hibernate had
     * already created these columns - nullable, from the entity - the clause
     * matched and V15 skipped them entirely, leaving them nullable forever.
     * Production ran the migration before those columns existed, so there
     * they are NOT NULL. Same code, same migrations, two different schemas,
     * and the failure only exists in one of them. V17 brings every
     * environment to the production shape so this cannot diverge again.
     *
     * The field initializers above cover the ordinary path; this covers
     * anything that explicitly nulls one afterwards, including Jackson
     * binding an explicit "bestseller": null from a request body.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = java.time.LocalDateTime.now();
        normaliseFlags();
    }

    /** Same guarantee on update - an explicit null must not reach the column. */
    @PreUpdate
    protected void onUpdate() {
        normaliseFlags();
    }

    private void normaliseFlags() {
        if (bestseller == null) bestseller = Boolean.FALSE;
        if (featured == null) featured = Boolean.FALSE;
        if (isTestData == null) isTestData = Boolean.FALSE;
        if (priceVerified == null) priceVerified = Boolean.FALSE;
    }
}