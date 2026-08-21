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

    // Auto-set on creation (@PrePersist below) - never client-supplied.
    // This is what "New Arrivals" actually sorts by.
    private java.time.LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = java.time.LocalDateTime.now();
    }
}