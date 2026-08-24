package com.gpstore.service;

import com.gpstore.dto.response.InventoryResponse;
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

    // ------------------------------------------------------------------
    // The admin screen's reads
    //
    // THESE RETURN DTOs, NOT ENTITIES, and that is a boundary rather than a
    // style preference. An InventoryResponse names the product, which lives
    // two lazy associations away (Inventory -> ProductVariant -> Product).
    // While the controller did the mapping, that lazy load happened outside
    // any transaction - which open-session-in-view quietly covered up by
    // holding a database connection for the whole request. With that turned
    // off (see spring.jpa.open-in-view) the same code is a
    // LazyInitializationException and a 500, which is the honest report of
    // what was always happening: queries being issued from the serialisation
    // layer.
    //
    // Mapping here means the associations are resolved while the session is
    // genuinely open, from a query that fetch-joined them, and what leaves
    // this class is finished data with nothing left to load.
    // ------------------------------------------------------------------

    // Was an unbounded findAll() - every inventory row ever created, loaded
    // into memory on every call to the admin inventory screen. Now genuinely
    // paginated (see admin_inventory_screen.dart's infinite scroll), not
    // just capped.
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InventoryResponse> getAll(
            org.springframework.data.domain.Pageable pageable) {
        return repository.findAllByOrderByIdAsc(pageable).map(InventoryResponse::from);
    }

    /** The entity, for callers inside this package that need to change it. */
    public Inventory getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory record not found"));
    }

    @Transactional(readOnly = true)
    public InventoryResponse getByIdAsResponse(Long id) {
        return InventoryResponse.from(getById(id));
    }

    /** The actual "what needs restocking" list - didn't exist before despite minimumStock already being a field. */
    @Transactional(readOnly = true)
    public List<InventoryResponse> getLowStock() {
        return repository.findLowStock().stream().map(InventoryResponse::from).toList();
    }

    @Transactional
    public InventoryResponse saveAsResponse(Inventory inventory) {
        return InventoryResponse.from(save(inventory));
    }

    @Transactional
    public InventoryResponse updateAsResponse(Long id, Inventory updated) {
        return InventoryResponse.from(update(id, updated));
    }

    @Transactional
    public InventoryResponse restockAsResponse(Long id, int quantity) {
        return InventoryResponse.from(restock(id, quantity));
    }

    @Transactional
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
     * Concurrency-safe stock decrement. The UPDATE itself is the lock:
     * {@code stock = stock - n WHERE stock >= n} either matches one row or
     * none. Two buyers of the last unit cannot both succeed. Throws
     * ConflictException rather than clamping to zero.
     */
    @Transactional
    public Inventory decrementForPurchase(Long productVariantId, int quantity) {
        if (quantity <= 0) {
            throw new BadRequestException("Purchase quantity must be positive");
        }
        int updated = repository.decrementIfAvailable(productVariantId, quantity);
        if (updated == 0) {
            Inventory inventory = repository.findByProductVariantId(productVariantId).orElse(null);
            if (inventory == null) {
                throw new ResourceNotFoundException("Inventory not found for product variant " + productVariantId);
            }
            throw new com.gpstore.exception.ConflictException("Insufficient stock");
        }
        return repository.findByProductVariantId(productVariantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inventory not found for product variant " + productVariantId));
    }

    private void validateNonNegativeStock(Inventory inventory) {
        if (inventory.getStock() != null && inventory.getStock() < 0) {
            throw new BadRequestException("Stock cannot be negative");
        }
    }
}
