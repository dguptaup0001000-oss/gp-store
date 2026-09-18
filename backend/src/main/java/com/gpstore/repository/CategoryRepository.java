package com.gpstore.repository;

import com.gpstore.entity.Category;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByActiveTrueOrderByNameAsc(Pageable pageable);

    /**
     * Named departments, by id, in the order a person reads them.
     *
     * <p>FOR "THE DEPARTMENTS THIS SHOP TRADES IN", which is answered from the
     * shop's own listings and so is already a bounded set - the shelf decides
     * how many there are, not the platform. Asking for them by id is the only
     * way to get all of them: the first version of that screen took the first
     * hundred categories of the whole taxonomy alphabetically and then kept the
     * shop's, so a merchant whose departments sorted after the hundredth was
     * shown an empty list and told they trade in nothing.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT c FROM Category c WHERE c.id IN :ids AND c.active = true "
            + "ORDER BY c.name ASC")
    List<Category> findActiveByIdIn(
            @org.springframework.data.repository.query.Param("ids")
            java.util.Collection<Long> ids);

    /**
     * Case-insensitive lookup for the bulk importer: a sheet says
     * "atta, rice & dal" and the row in the table says "Atta, Rice & Dal".
     * Rejecting that would fail every import a person types by hand.
     */
    Optional<Category> findByNameIgnoreCase(String name);
}