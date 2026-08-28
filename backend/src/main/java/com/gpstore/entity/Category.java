package com.gpstore.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.persistence.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.gpstore.upload.CatalogImageUrlSerializer;

import java.io.Serializable;
import java.math.BigDecimal;

@Entity
@Table(name = "categories")

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Category implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    @JsonSerialize(using = CatalogImageUrlSerializer.class)
    private String imageUrl;

    // GST rate as a percentage (e.g. 5.00 for 5%, 0 for exempt staples).
    // Nullable - treated as 0% if unset, never silently guessed otherwise.
    private BigDecimal gstRate;

    private Boolean active;
}