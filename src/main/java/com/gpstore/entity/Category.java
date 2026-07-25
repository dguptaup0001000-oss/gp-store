package com.gpstore.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "categories")

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    private String imageUrl;

    // GST rate as a percentage (e.g. 5.00 for 5%, 0 for exempt staples).
    // Nullable - treated as 0% if unset, never silently guessed otherwise.
    private BigDecimal gstRate;

    private Boolean active;
}