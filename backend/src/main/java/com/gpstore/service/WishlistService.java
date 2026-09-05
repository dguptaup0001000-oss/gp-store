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

    private final com.gpstore.catalog.shop.ShopPricedCatalogue shopPricedCatalogue;

    public WishlistService(WishlistRepository wishlistRepository,
                            ProductRepository productRepository,
                            CustomerService customerService,
                            com.gpstore.catalog.shop.ShopPricedCatalogue shopPricedCatalogue) {
        this.wishlistRepository = wishlistRepository;
        this.productRepository = productRepository;
        this.customerService = customerService;
        this.shopPricedCatalogue = shopPricedCatalogue;
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

        Wishlist saved = wishlistRepository.save(wishlist);
        return WishlistResponse.from(saved, shopPricedCatalogue.termsFor(saved.getProduct()));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<WishlistResponse> getAllWishlists(
            org.springframework.data.domain.Pageable pageable) {
        return wishlistRepository.findAll(pageable)
                .map(w -> WishlistResponse.from(w, shopPricedCatalogue.termsFor(w.getProduct())));
    }

    private static final int MY_WISHLIST_CAP = 100;

    @Transactional(readOnly = true)
    public List<WishlistResponse> getMyWishlist(Long customerId) {
        List<Wishlist> rows = wishlistRepository.findByCustomerId(customerId);
        if (rows.size() > MY_WISHLIST_CAP) {
            rows = rows.subList(0, MY_WISHLIST_CAP);
        }
        return rows.stream()
                .map(w -> WishlistResponse.from(w, shopPricedCatalogue.termsFor(w.getProduct())))
                .toList();
    }

    public void removeFromWishlist(Long id, Long customerId) {
        wishlistRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Wishlist item not found"));
        wishlistRepository.deleteById(id);
    }
}
