package com.gpstore.service;
import com.gpstore.entity.Customer;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.CartItem;

import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.repository.CartItemRepository;

import com.gpstore.entity.Cart;
import com.gpstore.repository.CartRepository;
import org.springframework.stereotype.Service;

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
        return cartRepository.findByCustomerId(customerId).orElse(null);
    }
    public Cart addToCart(Long customerId, Long variantId, Integer quantity) {

    Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> new RuntimeException("Customer not found"));

    ProductVariant variant = productVariantRepository.findById(variantId)
            .orElseThrow(() -> new RuntimeException("Variant not found"));

    Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> {
                Cart newCart = new Cart();
                newCart.setCustomer(customer);
                return cartRepository.save(newCart);
            });
            CartItem existingItem =
        cartItemRepository.findByCartIdAndProductVariantId(
                cart.getId(),
                variant.getId()
        ).orElse(null);

if (existingItem != null) {

    existingItem.setQuantity(
            existingItem.getQuantity() + quantity
    );

    existingItem.setTotalPrice(
            existingItem.getPrice().multiply(
                    java.math.BigDecimal.valueOf(existingItem.getQuantity())
            )
    );

    cartItemRepository.save(existingItem);

} else {

    CartItem cartItem = new CartItem();

    cartItem.setCart(cart);
    cartItem.setProductVariant(variant);
    cartItem.setQuantity(quantity);

    cartItem.setPrice(variant.getSellingPrice());

    cartItem.setTotalPrice(
            variant.getSellingPrice()
                    .multiply(java.math.BigDecimal.valueOf(quantity))
    );

    cartItemRepository.save(cartItem);

    cart.getItems().add(cartItem);
}

cart.setTotalItems(
        cart.getItems().stream()
                .mapToInt(CartItem::getQuantity)
                .sum()
);

cart.setTotalAmount(
        cart.getItems().stream()
                .map(CartItem::getTotalPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
);

return cartRepository.save(cart);
}
}