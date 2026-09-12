package com.gpstore.cart;

import com.gpstore.platform.CustomerOwnedRead;
import com.gpstore.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

/**
 * The basket, drawn as the several purchases it is (Part 2 §13).
 *
 * <p>ITS OWN ROUTE rather than a change to the existing cart response,
 * because an app in the field is already parsing that one and quietly
 * changing the shape of a live response is how a released build starts
 * showing an empty basket.
 *
 * <p>READ ACROSS SHOPS by name: a basket spanning three kiranas is precisely
 * the case CustomerOwnedRead exists for, and the customer is a party to every
 * row in it.
 */
@RestController
@RequestMapping("/api/carts")
public class CartByShopController {

    private final CartByShop cartByShop;
    private final CurrentUser currentUser;
    private final CustomerOwnedRead customerOwnedRead;

    public CartByShopController(CartByShop cartByShop, CurrentUser currentUser,
                                CustomerOwnedRead customerOwnedRead) {
        this.cartByShop = cartByShop;
        this.currentUser = currentUser;
        this.customerOwnedRead = customerOwnedRead;
    }

    /**
     * @param addressId optional - without one, no shop can quote delivery and
     *                  every section says so rather than showing zero
     */
    @GetMapping("/mine/by-shop")
    public CartByShop.Basket mine(@RequestParam(required = false) Long addressId) {
        Long me = currentUser.customerId();
        return customerOwnedRead.acrossShops(() -> cartByShop.forCustomer(me, addressId));
    }
}
