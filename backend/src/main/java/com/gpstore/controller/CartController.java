package com.gpstore.controller;

import com.gpstore.dto.response.CartResponse;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CartService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/carts")
public class CartController {

    private final CartService cartService;
    private final CurrentUser currentUser;

    public CartController(CartService cartService, CurrentUser currentUser) {
        this.cartService = cartService;
        this.currentUser = currentUser;
    }

    // Admin only - e.g. cart-abandonment analytics.
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public java.util.List<CartResponse> getAllCarts() {
        return cartService.getAllCarts().stream().map(CartResponse::from).toList();
    }

    // Returns only the logged-in customer's cart, with real product
    // name/brand included (see CartResponse for why that needs an explicit DTO).
    @GetMapping("/mine")
    public CartResponse getMyCart() {
        return CartResponse.from(cartService.getCustomerCart(currentUser.customerId()));
    }

    // Adds to the logged-in customer's cart - variantId/customerId can no longer
    // be supplied by the client to act on someone else's cart.
    @PostMapping("/add")
    public CartResponse addToCart(
            @RequestParam Long variantId,
            @RequestParam Integer quantity) {

        try {
            return CartResponse.from(cartService.addToCart(currentUser.customerId(), variantId, quantity));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent add-to-cart for the same variant
            // (a double-tap, two devices at once, or just concurrent load) -
            // CartService.addToCart checks for an existing CartItem row before
            // inserting a new one, so two near-simultaneous calls can both see
            // "doesn't exist yet" and both try to insert, and the loser hits
            // the (cart_id, product_variant_id) unique constraint. By the time
            // that happens the winner has already committed, so retrying once
            // now finds that row and merges the quantity into it instead of
            // surfacing a raw 500 to the customer.
            return CartResponse.from(cartService.addToCart(currentUser.customerId(), variantId, quantity));
        }
    }

    // Sets the exact quantity (not additive) - what a +/- stepper needs.
    // Setting quantity to 0 removes the item, same as any real cart UI.
    // Ownership of the cart item is verified server-side, never trusted from the ID alone.
    @PutMapping("/items/{cartItemId}")
    public CartResponse updateItemQuantity(
            @PathVariable Long cartItemId,
            @RequestParam Integer quantity) {

        return CartResponse.from(cartService.updateItemQuantity(currentUser.customerId(), cartItemId, quantity));
    }

    @DeleteMapping("/items/{cartItemId}")
    public CartResponse removeItem(@PathVariable Long cartItemId) {
        return CartResponse.from(cartService.removeItem(currentUser.customerId(), cartItemId));
    }
}
