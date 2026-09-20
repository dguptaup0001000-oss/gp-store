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


    /**

     * The category this one sits under, or null for a top-level category.

     *

     * <p>NULL IS NOT A MIGRATION GAP. Every category that existed before the

     * marketplace is top-level and stays that way: those rows are attached to

     * real listings in real shops, and reorganising them into a taxonomy

     * invented in a migration would be a script rearranging somebody's working

     * catalogue overnight. New categories can be nested from the start, and

     * the existing ones can be organised deliberately later.

     *

     * <p>A PLAIN ID RATHER THAN A @ManyToOne, so loading a category never drags

     * its ancestors along one query at a time. The picker reads the whole set

     * it needs in one statement and assembles the tree in memory.

     */

    @Column(name = "parent_id")

    private Long parentId;
}