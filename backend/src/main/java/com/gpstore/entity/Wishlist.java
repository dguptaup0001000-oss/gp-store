package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "wishlist", uniqueConstraints = @UniqueConstraint(
        name = "uk_wishlist_customer_product", columnNames = {"customer_id", "product_id"}))
public class Wishlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Customer customer;

    // See WishlistRepository.findByCustomerId's @EntityGraph - that's what
    // keeps this lazy relation from being lazy-loaded one query per item.
    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    private Boolean active;
}
