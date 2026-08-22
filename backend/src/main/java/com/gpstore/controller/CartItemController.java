package com.gpstore.controller;

import com.gpstore.entity.CartItem;
import com.gpstore.service.CartItemService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart-items")
public class CartItemController {

    private final CartItemService service;

    public CartItemController(CartItemService service) {
        this.service = service;
    }

    @PostMapping
    public CartItem save(@RequestBody CartItem item) {
        return service.save(item);
    }
@PostMapping("/add")
public CartItem addItem(@RequestBody CartItem item) {
    return service.addItem(item);
}
@DeleteMapping("/{id}")
public void removeItem(@PathVariable Long id) {
    service.removeItem(id);
}
@DeleteMapping("/cart/{cartId}")
public void clearCart(@PathVariable Long cartId) {
    service.clearCart(cartId);
}
    // GET /api/cart-items IS GONE, for the same reason as
    // GET /api/order-items - see OrderItemController. It returned every cart
    // item in the shop as a raw entity, which Jackson cannot serialise
    // through a Hibernate lazy proxy, so it answered 500 every time. A
    // customer's cart reaches them through CartResponse (GET /api/carts/mine).

    @GetMapping("/cart/{cartId}")
    public List<CartItem> getCartItems(@PathVariable Long cartId) {
        return service.getCartItems(cartId);
    }
}