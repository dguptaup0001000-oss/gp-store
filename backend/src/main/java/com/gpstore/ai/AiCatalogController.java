package com.gpstore.ai;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shop/ai-catalog")
public class AiCatalogController {
    private final AiCatalogService catalog;

    public AiCatalogController(AiCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/jobs")
    public AiCatalogService.JobView start(@RequestBody AiCatalogService.StartRequest request) {
        return catalog.start(request);
    }

    @GetMapping("/jobs")
    public List<AiCatalogService.JobView> jobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.jobs(page, size);
    }

    @PutMapping("/drafts/{id}")
    public AiCatalogService.DraftView update(
            @PathVariable long id, @RequestBody AiCatalogService.DraftInput input) {
        return catalog.update(id, input);
    }

    @PostMapping("/drafts/{id}/approve")
    public AiCatalogService.DraftView approve(@PathVariable long id) {
        return catalog.approve(id);
    }

    @PostMapping("/drafts/batch-approve")
    public List<AiCatalogService.DraftView> approveBatch(
            @RequestBody AiCatalogService.BatchApproveRequest request) {
        return catalog.approveBatch(request);
    }

    @PostMapping("/drafts/{id}/reject")
    public AiCatalogService.DraftView reject(@PathVariable long id) {
        return catalog.reject(id);
    }
}
