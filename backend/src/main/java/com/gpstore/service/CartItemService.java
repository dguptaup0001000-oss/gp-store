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
        List<CartItem> items = repository.findByCartId(cartId);
        repository.deleteAll(items);

        Cart cart = cartRepository.findById(cartId).orElse(null);
        if (cart != null) {
            cart.setTotalItems(0);
            cart.setTotalAmount(BigDecimal.ZERO);
            cartRepository.save(cart);
        }
    }

    public List<CartItem> getAll() {
        return repository.findAll();
    }

    public List<CartItem> getCartItems(Long cartId) {
        return repository.findByCartId(cartId);
    }

    public boolean isCartEmpty(Long cartId) {
        return repository.findByCartId(cartId).isEmpty();
    }
}