package com.gpstore.controller;

import com.gpstore.entity.CartItem;
import com.gpstore.service.CartItemService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart-items")
public class CartItemController {

    private final CartItemService service;
    private final com.gpstore.service.CartService cartService;

    public CartItemController(CartItemService service,
                              com.gpstore.service.CartService cartService) {
        this.service = service;
        this.cartService = cartService;
    }

    @PostMapping
    public CartItem save(@RequestBody CartItem item) {
        return service.save(item);
    }
@PostMapping("/add")
public CartItem addItem(@RequestBody CartItem item) {
    return service.addItem(item);
}
// THE CALLER'S OWN LINES. See CartItemService.removeItemAsStaff for what
// these two routes could do to a basket before that was true.
@DeleteMapping("/{id}")
public void removeItem(@PathVariable Long id) {
    service.removeItemAsStaff(id);
}
@DeleteMapping("/cart/{cartId}")
public void clearCart(@PathVariable Long cartId) {
    service.clearCartAsStaff(cartId);
}
    // GET /api/cart-items IS GONE, for the same reason as
    // GET /api/order-items - see OrderItemController. It returned every cart
    // item in the shop as a raw entity, which Jackson cannot serialise
    // through a Hibernate lazy proxy, so it answered 500 every time. A
    // customer's cart reaches them through CartResponse (GET /api/carts/mine).

    /**
     * One basket, for staff answering a question about it.
     *
     * <p>RETURNS A DTO, AND THAT IS THE FIX FOR TWO DEFECTS AT ONCE. It used
     * to return {@code List<CartItem>} - raw entities whose {@code cart} and
     * {@code productVariant} are lazy proxies, which Jackson cannot write - so
     * it answered 500 to every caller who ever called it, the same way
     * {@code GET /api/cart-items} did before that one was removed. It was also
     * any cart, read by anybody holding CUSTOMERS_VIEW, which every shop owner
     * holds. CartService.getCartForStaff answers with the caller's own lines.
     *
     * <p>The response is now the same {@code CartResponse} shape the customer's
     * own basket uses, rather than a bare array. Nothing consumed the array: it
     * could not be produced.
     */
    @GetMapping("/cart/{cartId}")
    public com.gpstore.dto.response.CartResponse getCartItems(@PathVariable Long cartId) {
        return cartService.getCartForStaff(cartId);
    }
}