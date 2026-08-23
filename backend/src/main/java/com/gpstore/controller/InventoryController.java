package com.gpstore.controller;

import com.gpstore.dto.response.InventoryResponse;
import com.gpstore.entity.Inventory;
import com.gpstore.service.InventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Admin only across this whole controller (enforced in SecurityConfig).
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping
    public InventoryResponse create(@RequestBody Inventory inventory) {
        return inventoryService.saveAsResponse(inventory);
    }

    @GetMapping
    public Page<InventoryResponse> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return inventoryService.getAll(pageable);
    }

    @GetMapping("/{id}")
    public InventoryResponse getById(@PathVariable Long id) {
        return inventoryService.getByIdAsResponse(id);
    }

    // The actual restock list - items at or below their reorder point.
    @GetMapping("/low-stock")
    public List<InventoryResponse> getLowStock() {
        return inventoryService.getLowStock();
    }

    // Full manual correction (e.g. stock-take reconciliation).
    @PutMapping("/{id}")
    public InventoryResponse update(@PathVariable Long id, @RequestBody Inventory inventory) {
        return inventoryService.updateAsResponse(id, inventory);
    }

    // The real day-to-day operation: "we received N more units" - additive, audited.
    @PutMapping("/{id}/restock")
    public InventoryResponse restock(@PathVariable Long id, @RequestParam int quantity) {
        return inventoryService.restockAsResponse(id, quantity);
    }
}
