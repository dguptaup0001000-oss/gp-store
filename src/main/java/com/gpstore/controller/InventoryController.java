package com.gpstore.controller;

import com.gpstore.dto.response.InventoryResponse;
import com.gpstore.entity.Inventory;
import com.gpstore.service.InventoryService;
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
        return InventoryResponse.from(inventoryService.save(inventory));
    }

    @GetMapping
    public List<InventoryResponse> getAll() {
        return inventoryService.getAll().stream().map(InventoryResponse::from).toList();
    }

    @GetMapping("/{id}")
    public InventoryResponse getById(@PathVariable Long id) {
        return InventoryResponse.from(inventoryService.getById(id));
    }

    // The actual restock list - items at or below their reorder point.
    @GetMapping("/low-stock")
    public List<InventoryResponse> getLowStock() {
        return inventoryService.getLowStock().stream().map(InventoryResponse::from).toList();
    }

    // Full manual correction (e.g. stock-take reconciliation).
    @PutMapping("/{id}")
    public InventoryResponse update(@PathVariable Long id, @RequestBody Inventory inventory) {
        return InventoryResponse.from(inventoryService.update(id, inventory));
    }

    // The real day-to-day operation: "we received N more units" - additive, audited.
    @PutMapping("/{id}/restock")
    public InventoryResponse restock(@PathVariable Long id, @RequestParam int quantity) {
        return InventoryResponse.from(inventoryService.restock(id, quantity));
    }
}
