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
    /**
     * Admin cart-abandonment listing. Paged with a server-side cap of 100,
     * matching OrderController. .map() on the Page keeps the pagination
     * metadata intact while converting each entity to its DTO.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public org.springframework.data.domain.Page<CartResponse> getAllCarts(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return cartService.getAllCarts(org.springframework.data.domain.PageRequest.of(
                        Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        org.springframework.data.domain.Sort.by("id")))
                .map(CartResponse::from);
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

        // Lost a race with a concurrent add-to-cart for the same variant on
        // this SAME account (a double-tap, two devices at once) -
        // CartService.addToCart checks for an existing CartItem row before
        // inserting a new one, so near-simultaneous calls can all see
        // "doesn't exist yet" and all try to insert, and every loser hits
        // the (cart_id, product_variant_id) unique constraint. Each retry
        // only needs to beat ONE prior winner's insert, which has already
        // committed by the time the exception fires - so a bounded loop
        // (not just one retry) stays correct even if more than two calls
        // pile up on the exact same cart+variant at once, at negligible
        // cost since that's already a rare edge case in real traffic (a
        // cart belongs to one customer, so this can only happen within one
        // person's own double-tap, not across different customers).
        DataIntegrityViolationException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return CartResponse.from(cartService.addToCart(currentUser.customerId(), variantId, quantity));
            } catch (DataIntegrityViolationException e) {
                lastFailure = e;
            }
        }
        throw lastFailure;
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
