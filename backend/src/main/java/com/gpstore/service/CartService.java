package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.CartItem;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;

import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.repository.CartItemRepository;

import com.gpstore.entity.Cart;
import com.gpstore.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository productVariantRepository;
    private final CartItemRepository cartItemRepository;

    public CartService(
            CartRepository cartRepository,
            CustomerRepository customerRepository,
            ProductVariantRepository productVariantRepository,
            CartItemRepository cartItemRepository) {

        this.cartRepository = cartRepository;
        this.customerRepository = customerRepository;
        this.productVariantRepository = productVariantRepository;
        this.cartItemRepository = cartItemRepository;
    }

    public Cart saveCart(Cart cart) {
        return cartRepository.save(cart);
    }

    public List<Cart> getAllCarts() {
        return cartRepository.findAll();
    }

    public Cart getCustomerCart(Long customerId) {
        return cartRepository.findByCustomerIdWithItemsFetched(customerId).orElse(null);
    }

    @Transactional
    @io.micrometer.core.annotation.Timed(value = "cart.add", description = "Cart add mutation", percentiles = {0.5, 0.95, 0.99})
    public Cart addToCart(Long customerId, Long variantId, Integer quantity) {

        if (quantity == null || quantity <= 0) {
            throw new BadRequestException("Quantity must be positive");
        }

        // Locked for the rest of this transaction (see
        // CustomerRepository.findByIdForUpdate) - a second concurrent
        // addToCart/updateItemQuantity/removeItem for this same customer
        // blocks here until this one commits, instead of racing the cart
        // lookup-or-create and item quantity increment below. The customer
        // row (unlike the cart row) is guaranteed to already exist, so this
        // also closes the "this customer's very first cart, created twice
        // at once" race that locking the cart row alone couldn't.
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));

        // Same check as checkout - catching it here means the customer
        // finds out immediately when they try to add it, not later at
        // checkout after it's been sitting in their cart.
        if (variant.getAvailable() == null || !variant.getAvailable()
                || variant.getProduct() == null
                || variant.getProduct().getActive() == null
                || !variant.getProduct().getActive()) {
            throw new ConflictException(
                    (variant.getProduct() != null ? variant.getProduct().getName() : "This item")
                            + " is currently unavailable");
        }

        Cart cart = cartRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setCustomer(customer);
                    return cartRepository.save(newCart);
                });

        CartItem existingItem = cartItemRepository
                .findByCartIdAndProductVariantId(cart.getId(), variant.getId())
                .orElse(null);

        if (existingItem != null) {
            existingItem.setQuantity(existingItem.getQuantity() + quantity);
            existingItem.setTotalPrice(existingItem.getPrice().multiply(BigDecimal.valueOf(existingItem.getQuantity())));
            cartItemRepository.save(existingItem);
        } else {
            CartItem cartItem = new CartItem();
            cartItem.setCart(cart);
            cartItem.setProductVariant(variant);
            cartItem.setQuantity(quantity);
            cartItem.setPrice(variant.getSellingPrice());
            cartItem.setTotalPrice(variant.getSellingPrice().multiply(BigDecimal.valueOf(quantity)));
            cartItemRepository.save(cartItem);
            cart.getItems().add(cartItem);
        }

        recalculateAndSave(cart);
        return fetchWithItems(customerId);
    }

    /**
     * Sets the EXACT quantity (not additive, unlike addToCart) - this is
     * what the spec's "quantity increase/decrease" controls actually need,
     * since a +/- stepper wants to set an absolute new value, not add a
     * delta each tap. Setting quantity to 0 or less removes the item
     * entirely - matches how every real cart UI treats "decrease to zero".
     * Ownership is verified via the cart's customerId, not just the item ID -
     * this is what stops one customer from editing another's cart item by ID.
     */
    @Transactional
    @io.micrometer.core.annotation.Timed(value = "cart.update_quantity", description = "Cart quantity mutation", percentiles = {0.5, 0.95, 0.99})
    public Cart updateItemQuantity(Long customerId, Long cartItemId, int newQuantity) {
        // See CustomerRepository.findByIdForUpdate's doc comment - locks out
        // any concurrent addToCart/updateItemQuantity/removeItem for this
        // customer until this transaction commits.
        customerRepository.findByIdForUpdate(customerId);

        CartItem item = getOwnedCartItem(customerId, cartItemId);
        Cart cart = item.getCart();

        if (newQuantity <= 0) {
            cart.getItems().remove(item);
            cartItemRepository.delete(item);
        } else {
            item.setQuantity(newQuantity);
            item.setTotalPrice(item.getPrice().multiply(BigDecimal.valueOf(newQuantity)));
            cartItemRepository.save(item);
        }

        recalculateAndSave(cart);
        return fetchWithItems(customerId);
    }

    @Transactional
    @io.micrometer.core.annotation.Timed(value = "cart.remove", description = "Cart remove mutation", percentiles = {0.5, 0.95, 0.99})
    public Cart removeItem(Long customerId, Long cartItemId) {
        // See CustomerRepository.findByIdForUpdate's doc comment.
        customerRepository.findByIdForUpdate(customerId);

        CartItem item = getOwnedCartItem(customerId, cartItemId);
        Cart cart = item.getCart();

        cart.getItems().remove(item);
        cartItemRepository.delete(item);

        recalculateAndSave(cart);
        return fetchWithItems(customerId);
    }

    /** See CartRepository.findByCustomerIdWithItemsFetched's doc comment - avoids N+1 on the response conversion. */
    private Cart fetchWithItems(Long customerId) {
        return cartRepository.findByCustomerIdWithItemsFetched(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found"));
    }

    /** Throws if the item doesn't exist or belongs to a different customer's cart - closes the IDOR. */
    private CartItem getOwnedCartItem(Long customerId, Long cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));

        if (item.getCart() == null || item.getCart().getCustomer() == null
                || !item.getCart().getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Cart item not found");
        }

        return item;
    }

    private Cart recalculateAndSave(Cart cart) {
        cart.setTotalItems(cart.getItems().stream().mapToInt(CartItem::getQuantity).sum());
        cart.setTotalAmount(cart.getItems().stream()
                .map(CartItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return cartRepository.save(cart);
    }
}
