package com.gpstore.service;

import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.repository.CartItemRepository;
import com.gpstore.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartItemService {

    private final CartItemRepository repository;
    private final CartRepository cartRepository;

    public CartItemService(CartItemRepository repository, CartRepository cartRepository) {
        this.repository = repository;
        this.cartRepository = cartRepository;
    }

    public CartItem save(CartItem item) {
        return repository.save(item);
    }

    public CartItem addItem(CartItem item) {
        return repository.save(item);
    }

    public void removeItem(Long id) {
        repository.deleteById(id);
    }

    /**
     * Deletes every item AND zeroes the parent Cart's own totalItems/
     * totalAmount columns - those are denormalized (recalculated and saved
     * by CartService.recalculateAndSave on every add/update/remove) rather
     * than derived live from the items list, and this method previously
     * only ever deleted the CartItem rows. That left a cleared cart (after
     * checkout, or this method's own /cart-items/cart/{id} admin endpoint)
     * with an empty items list but stale non-zero totals still on the Cart
     * row itself - exactly what CartResponse.from() then serialized back
     * out, showing "2 items, ₹60" on the cart badge/summary bar for a cart
     * that GET /api/cart/my-cart's own items array confirms is empty.
     */
    @Transactional
    public void clearCart(Long cartId) {
        // One DELETE, not a SELECT followed by one DELETE per row. The
        // previous version loaded every CartItem into the persistence
        // context purely to hand it to deleteAll() - nothing read those
        // entities. On the checkout path that happened while inventory row
        // locks were held, so every one of those round trips extended the
        // window other customers' checkouts could be blocked for.
        repository.deleteByCartId(cartId);

        // The Cart's own totals are denormalized (recalculated and saved by
        // CartService.recalculateAndSave on every add/update/remove) rather
        // than derived live from the items list. Zeroing them here is what
        // stops a cleared cart still reporting "2 items, Rs 60" on the cart
        // badge while its items array is empty.
        //
        // Left as a managed-entity update rather than a bulk UPDATE: it is a
        // single row, and going through the entity keeps it consistent with
        // how every other cart mutation writes these fields.
        Cart cart = cartRepository.findById(cartId).orElse(null);
        if (cart != null) {
            cart.setTotalItems(0);
            cart.setTotalAmount(BigDecimal.ZERO);
            cartRepository.save(cart);
        }
    }

    // Unused by the current frontend (confirmed) and admin-only, but was a
    // plain findAll() - every cart item across every customer's cart, ever.
    // Capped defensively rather than left as live unbounded API surface.
    private static final int ADMIN_LIST_CAP = 500;

    public List<CartItem> getAll() {
        return repository.findAll(org.springframework.data.domain.PageRequest.of(0, ADMIN_LIST_CAP)).getContent();
    }

    public List<CartItem> getCartItems(Long cartId) {
        return repository.findByCartId(cartId);
    }

    /**
     * Cart items with variant, product and category already fetched - for
     * the two callers that read all three per line (checkout preview and
     * place order). See CartItemRepository.findByCartIdForCheckout for the
     * measured cost of not doing this.
     */
    public List<CartItem> getCartItemsForCheckout(Long cartId) {
        return repository.findByCartIdForCheckout(cartId);
    }

    public boolean isCartEmpty(Long cartId) {
        return repository.findByCartId(cartId).isEmpty();
    }
}