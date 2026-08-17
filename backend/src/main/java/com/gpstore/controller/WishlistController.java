package com.gpstore.controller;

import com.gpstore.dto.request.WishlistRequest;
import com.gpstore.dto.response.WishlistResponse;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CustomerService;
import com.gpstore.service.WishlistService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    public WishlistResponse createWishlist(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid WishlistRequest request) {
            return wishlistService.saveWishlist(currentUser.customerId(), request);
        }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public Page<WishlistResponse> getAllWishlists(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return wishlistService.getAllWishlists(pageable);
    }

    // Returns only the logged-in customer's wishlist.
    @GetMapping("/mine")
    public List<WishlistResponse> getMyWishlist() {
        return wishlistService.getMyWishlist(currentUser.customerId());
    }

    @DeleteMapping("/{id}")
    public void removeFromWishlist(@PathVariable Long id) {
        wishlistService.removeFromWishlist(id, currentUser.customerId());
    }
}
