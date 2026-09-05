package com.gpstore.catalog.importer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogImportProblemRepository extends JpaRepository<CatalogImportProblem, Long> {
    List<CatalogImportProblem> findByRunIdOrderByRowNumberAsc(Long runId);
    void deleteByRunId(Long runId);
}
