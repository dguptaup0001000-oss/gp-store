package com.gpstore.repository;

import com.gpstore.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductVariantRepository
        extends JpaRepository<ProductVariant, Long> {

}