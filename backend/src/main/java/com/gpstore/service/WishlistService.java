package com.gpstore.service;

import com.gpstore.dto.request.WishlistRequest;
import com.gpstore.dto.response.WishlistResponse;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Product;
import com.gpstore.entity.Wishlist;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.WishlistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;
    private final CustomerService customerService;

    public WishlistService(WishlistRepository wishlistRepository,
                            ProductRepository productRepository,
                            CustomerService customerService) {
        this.wishlistRepository = wishlistRepository;
        this.productRepository = productRepository;
        this.customerService = customerService;
    }

    @Transactional
    public WishlistResponse saveWishlist(Long customerId, WishlistRequest request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        Customer customer = customerService.getById(customerId);

        Wishlist wishlist = new Wishlist();
        wishlist.setCustomer(customer);
        wishlist.setProduct(product);
        wishlist.setActive(true);

        return WishlistResponse.from(wishlistRepository.save(wishlist));
    }

    @Transactional(readOnly = true)
    public List<WishlistResponse> getAllWishlists() {
        return wishlistRepository.findAll().stream().map(WishlistResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<WishlistResponse> getMyWishlist(Long customerId) {
        return wishlistRepository.findByCustomerId(customerId).stream().map(WishlistResponse::from).toList();
    }

    public void removeFromWishlist(Long id, Long customerId) {
        wishlistRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Wishlist item not found"));
        wishlistRepository.deleteById(id);
    }
}
