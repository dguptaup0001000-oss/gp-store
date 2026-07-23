package com.gpstore.controller;

import com.gpstore.entity.Wishlist;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CustomerService;
import com.gpstore.service.WishlistService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wishlists")
public class WishlistController {

    private final WishlistService wishlistService;
    private final CustomerService customerService;
    private final CurrentUser currentUser;

    public WishlistController(WishlistService wishlistService, CustomerService customerService, CurrentUser currentUser) {
        this.wishlistService = wishlistService;
        this.customerService = customerService;
        this.currentUser = currentUser;
    }

    // Adds to the logged-in customer's wishlist - ownership is never taken from the client.
    @PostMapping
    public Wishlist createWishlist(@RequestBody Wishlist wishlist) {
        wishlist.setCustomer(customerService.getById(currentUser.customerId()));
        return wishlistService.saveWishlist(wishlist);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<Wishlist> getAllWishlists() {
        return wishlistService.getAllWishlists();
    }

    // Returns only the logged-in customer's wishlist.
    @GetMapping("/mine")
    public List<Wishlist> getMyWishlist() {
        return wishlistService.getMyWishlist(currentUser.customerId());
    }

    @DeleteMapping("/{id}")
    public void removeFromWishlist(@PathVariable Long id) {
        wishlistService.removeFromWishlist(id, currentUser.customerId());
    }
}
