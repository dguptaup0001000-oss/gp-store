package com.gpstore.catalog.importer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogImportRunRepository extends JpaRepository<CatalogImportRun, Long> {
    Page<CatalogImportRun> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
