package com.gpstore.service;

import com.gpstore.entity.Inventory;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryService {

    private final InventoryRepository repository;
    private final AuditLogService auditLogService;

    public InventoryService(InventoryRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    public Inventory save(Inventory inventory) {
        validateNonNegativeStock(inventory);
        return repository.save(inventory);
    }

    // Was an unbounded findAll() - every inventory row ever created, loaded
    // into memory on every call to the admin inventory screen. Now genuinely
    // paginated (see admin_inventory_screen.dart's infinite scroll), not
    // just capped.
    public org.springframework.data.domain.Page<Inventory> getAll(org.springframework.data.domain.Pageable pageable) {
        return repository.findAllByOrderByIdAsc(pageable);
    }

    public Inventory getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory record not found"));
    }

    /** The actual "what needs restocking" list - didn't exist before despite minimumStock already being a field. */
    public List<Inventory> getLowStock() {
        return repository.findLowStock();
    }

    public Inventory update(Long id, Inventory updated) {
        Inventory existing = getById(id);

        existing.setStock(updated.getStock());
        existing.setReservedStock(updated.getReservedStock());
        existing.setMinimumStock(updated.getMinimumStock());
        existing.setMaximumStock(updated.getMaximumStock());

        validateNonNegativeStock(existing);

        return repository.save(existing);
    }

    /**
     * Restocking is additive and audited - "I received 50 more units" rather
     * than "set stock to some number I calculated myself", which is both safer
     * (no risk of accidentally wiping out real stock with a stale number) and
     * gives a real audit trail of every restock event.
     */
    @Transactional
    public Inventory restock(Long id, int quantity) {
        if (quantity <= 0) {
            throw new BadRequestException("Restock quantity must be positive");
        }

        Inventory inventory = getById(id);
        int previousStock = inventory.getStock() == null ? 0 : inventory.getStock();
        inventory.setStock(previousStock + quantity);

        Inventory saved = repository.save(inventory);

        auditLogService.log("INVENTORY_RESTOCKED", "Inventory", saved.getId(),
                "added=" + quantity + ", stock: " + previousStock + " -> " + saved.getStock());

        return saved;
    }

    public Inventory getByProductVariant(Long productVariantId) {
        return repository.findByProductVariantId(productVariantId)
                .orElse(null);
    }

    /** Locks the row for the current transaction - use inside placeOrder to avoid overselling. */
    public Inventory getByProductVariantForUpdate(Long productVariantId) {
        return repository.findByProductVariantIdForUpdate(productVariantId)
                .orElse(null);
    }

    /**
     * Concurrency-safe stock decrement: locks the row for the duration of
     * this transaction (see getByProductVariantForUpdate above), then
     * checks and decrements under that lock - the same pattern
     * OrderService.placeOrder() already applies inline for real checkout,
     * extracted here as its own reusable, directly-testable unit (see
     * ConcurrencyIntegrationTest). Throws ConflictException rather than
     * silently clamping to zero - a caller must never treat insufficient
     * stock as a successful purchase.
     */
    @Transactional
    public Inventory decrementForPurchase(Long productVariantId, int quantity) {
        Inventory inventory = getByProductVariantForUpdate(productVariantId);
        if (inventory == null) {
            throw new ResourceNotFoundException("Inventory not found for product variant " + productVariantId);
        }
        if (inventory.getStock() == null || inventory.getStock() < quantity) {
            throw new com.gpstore.exception.ConflictException("Insufficient stock");
        }
        inventory.setStock(inventory.getStock() - quantity);
        return repository.save(inventory);
    }

    private void validateNonNegativeStock(Inventory inventory) {
        if (inventory.getStock() != null && inventory.getStock() < 0) {
            throw new BadRequestException("Stock cannot be negative");
        }
    }
}
