package com.gpstore.service;

import com.gpstore.entity.CartItem;
import com.gpstore.repository.CartItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CartItemService {

    private final CartItemRepository repository;

    public CartItemService(CartItemRepository repository) {
        this.repository = repository;
    }

    public CartItem save(CartItem item) {
        return repository.save(item);
    }

    public CartItem addItem(CartItem item) {
        return repository.save(item);
    }

    public void removeItem(Long id) {
        repository.deleteById(id);
    }

    public void clearCart(Long cartId) {
        List<CartItem> items = repository.findByCartId(cartId);
        repository.deleteAll(items);
    }

    public List<CartItem> getAll() {
        return repository.findAll();
    }

    public List<CartItem> getCartItems(Long cartId) {
        return repository.findByCartId(cartId);
    }

    public boolean isCartEmpty(Long cartId) {
        return repository.findByCartId(cartId).isEmpty();
    }
}