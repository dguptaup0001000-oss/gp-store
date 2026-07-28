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

    @ManyToOne
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