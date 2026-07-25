package com.gpstore.controller;

import com.gpstore.entity.Cart;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CartService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carts")
public class CartController {

    private final CartService cartService;
    private final CurrentUser currentUser;

    public CartController(CartService cartService, CurrentUser currentUser) {
        this.cartService = cartService;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<Cart> getAllCarts() {
        return cartService.getAllCarts();
    }

    // Returns only the logged-in customer's cart.
    @GetMapping("/mine")
    public Cart getMyCart() {
        return cartService.getCustomerCart(currentUser.customerId());
    }

    // Adds to the logged-in customer's cart - variantId/customerId can no longer
    // be supplied by the client to act on someone else's cart.
    @PostMapping("/add")
    public Cart addToCart(
            @RequestParam Long variantId,
            @RequestParam Integer quantity) {

        return cartService.addToCart(currentUser.customerId(), variantId, quantity);
    }
}
