package com.gpstore.service;

import com.gpstore.entity.Wishlist;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.WishlistRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WishlistService {

    private final WishlistRepository wishlistRepository;

    public WishlistService(WishlistRepository wishlistRepository) {
        this.wishlistRepository = wishlistRepository;
    }

    public Wishlist saveWishlist(Wishlist wishlist) {
        return wishlistRepository.save(wishlist);
    }

    public List<Wishlist> getAllWishlists() {
        return wishlistRepository.findAll();
    }

    public List<Wishlist> getMyWishlist(Long customerId) {
        return wishlistRepository.findByCustomerId(customerId);
    }

    public void removeFromWishlist(Long id, Long customerId) {
        wishlistRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Wishlist item not found"));
        wishlistRepository.deleteById(id);
    }
}
