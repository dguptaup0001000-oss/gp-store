package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "wishlist")
public class Wishlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Customer customer;

    // Deliberately left EAGER: WishlistController.getMyWishlist() returns
    // raw Wishlist entities with no @Transactional on the service method, so
    // Jackson serializes after the session closes - LAZY here throws
    // LazyInitializationException on that exact endpoint. Revisit alongside
    // a DTO refactor of WishlistController, not in isolation.
    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    private Boolean active;
}
