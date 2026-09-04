package com.gpstore.repository;

import com.gpstore.entity.Category;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByActiveTrueOrderByNameAsc(Pageable pageable);

    /**
     * Case-insensitive lookup for the bulk importer: a sheet says
     * "atta, rice & dal" and the row in the table says "Atta, Rice & Dal".
     * Rejecting that would fail every import a person types by hand.
     */
    Optional<Category> findByNameIgnoreCase(String name);
}