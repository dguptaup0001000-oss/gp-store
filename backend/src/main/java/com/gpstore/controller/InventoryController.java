package com.gpstore.controller;

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
    public Inventory create(@RequestBody Inventory inventory) {
        return inventoryService.save(inventory);
    }

    @GetMapping
    public List<Inventory> getAll() {
        return inventoryService.getAll();
    }

    @GetMapping("/{id}")
    public Inventory getById(@PathVariable Long id) {
        return inventoryService.getById(id);
    }

    // The actual restock list - items at or below their reorder point.
    @GetMapping("/low-stock")
    public List<Inventory> getLowStock() {
        return inventoryService.getLowStock();
    }

    // Full manual correction (e.g. stock-take reconciliation).
    @PutMapping("/{id}")
    public Inventory update(@PathVariable Long id, @RequestBody Inventory inventory) {
        return inventoryService.update(id, inventory);
    }

    // The real day-to-day operation: "we received N more units" - additive, audited.
    @PutMapping("/{id}/restock")
    public Inventory restock(@PathVariable Long id, @RequestParam int quantity) {
        return inventoryService.restock(id, quantity);
    }
}
