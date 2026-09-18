package com.gpstore.catalog.importer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogImportProblemRepository extends JpaRepository<CatalogImportProblem, Long> {
    /**
     * The problems of a run THIS shop made.
     *
     * <p>WAS findByRunId, WITH NOTHING ELSE. CatalogImportProblem carries no
     * shop of its own - it hangs off the run - and the two routes that read it
     * ({@code GET /api/admin/catalog/import/{runId}/problems} and its .csv
     * twin) took the run id straight out of the URL. The gate is SYSTEM_ADMIN,
     * which Role.ADMIN carries, so any merchant could count upwards through
     * run ids and read another shop's rejected spreadsheet rows: their product
     * names, SKUs, selling prices and MRPs, with the reason each was refused.
     *
     * <p>CatalogImportRun IS shop-owned, so joining through it is all the
     * narrowing this needs - and putting it in the query rather than in the
     * controller means the next route that reads problems inherits it.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT p FROM CatalogImportProblem p "
            + "WHERE p.runId = :runId "
            + "  AND EXISTS (SELECT 1 FROM CatalogImportRun r WHERE r.id = p.runId) "
            + "ORDER BY p.rowNumber ASC")
    List<CatalogImportProblem> findForRunOnThisShop(
            @org.springframework.data.repository.query.Param("runId") Long runId);
    void deleteByRunId(Long runId);
}
