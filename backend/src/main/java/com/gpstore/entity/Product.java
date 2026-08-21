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

    private Boolean bestseller;

    private Boolean featured;

    /**
     * Seeded test data, and the flag the pre-launch cleanup keys on.
     *
     * Defaults FALSE in the database precisely so that anything a human adds
     * through the admin screens is never swept up by that cleanup. A product
     * has to be deliberately marked to be deletable by it.
     */
    @Column(name = "is_test_data")
    private Boolean isTestData;

    /**
     * Separate from isTestData on purpose: someone can confirm a product is
     * genuinely stocked long before anyone checks its price against a shelf.
     * Collapsing the two would let a half-checked product read as verified.
     */
    @Column(name = "price_verified")
    private Boolean priceVerified;

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

    @PrePersist
    protected void onCreate() {
        this.createdAt = java.time.LocalDateTime.now();
    }
}