package com.gpstore.service;

import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.repository.CartItemRepository;
import com.gpstore.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartItemService {

    private final CartItemRepository repository;
    private final CartRepository cartRepository;
    private final com.gpstore.platform.ShopCustomers shopCustomers;

    public CartItemService(CartItemRepository repository, CartRepository cartRepository,
                           com.gpstore.platform.ShopCustomers shopCustomers) {
        this.repository = repository;
        this.cartRepository = cartRepository;
        this.shopCustomers = shopCustomers;
    }

    /**
     * Removing one line from somebody's basket, as staff.
     *
     * <p>WHAT THE ROUTE BEHIND THIS COULD DO. {@code DELETE /api/cart-items/{id}}
     * is gated by CUSTOMERS_MANAGE, which {@code Role.ADMIN} carries, so every
     * shop owner on GP-STORE held it - over {@code deleteById}, on an entity
     * that is deliberately not tenant filtered because a basket spans shops.
     * One merchant could therefore delete a line another merchant had sold
     * into: the shopper reaches the checkout and the rival's item is simply
     * gone, with no error and nothing in the order history to explain it.
     *
     * <p>A LINE CARRIES THE SHOP IT CAME OFF. {@code cart_items.shop_id} is
     * stamped by the server when the line is added and never accepted from a
     * client, so "is this line mine" is a fact this method can check. The
     * platform console, with no shop in scope, still removes any line.
     */
    @Transactional
    public void removeItemAsStaff(Long id) {
        CartItem item = repository.findById(id).orElse(null);
        if (item == null) {
            return; // deleting what is not there is not an error, and never was
        }
        Long myShop = shopCustomers.actingShopId();
        if (myShop != null && !myShop.equals(item.getShopId())) {
            throw new com.gpstore.platform.CrossShopAccessException(
                    "That basket line was not sold by this shop.");
        }
        Long cartId = item.getCart() == null ? null : item.getCart().getId();
        repository.delete(item);
        repository.flush();
        retotal(cartId);
    }

    /**
     * Emptying a basket, as staff - of this shop's lines only.
     *
     * <p>Same route family and the same defect as {@link #removeItemAsStaff},
     * one blast radius larger: {@code DELETE /api/cart-items/cart/{cartId}}
     * deleted every line in a named basket, whoever had sold them. A
     * shopkeeper clears what they put there; the platform clears the basket.
     *
     * <p>Checkout does NOT come through here - it calls {@link #clearCart},
     * which still empties the basket completely, because a basket that has
     * become orders is finished whichever shops it spanned.
     */
    @Transactional
    public void clearCartAsStaff(Long cartId) {
        Long myShop = shopCustomers.actingShopId();
        if (myShop == null) {
            clearCart(cartId);
            return;
        }
        List<CartItem> mine = repository.findByCartId(cartId).stream()
                .filter(line -> myShop.equals(line.getShopId()))
                .toList();
        if (mine.isEmpty()) {
            return;
        }
        repository.deleteAll(mine);
        repository.flush();
        retotal(cartId);
    }

    /** Re-derives the basket's stored totals from whatever lines are left. */
    private void retotal(Long cartId) {
        if (cartId == null) {
            return;
        }
        Cart cart = cartRepository.findById(cartId).orElse(null);
        if (cart == null) {
            return;
        }
        int items = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (CartItem line : repository.findByCartId(cartId)) {
            items += line.getQuantity() == null ? 0 : line.getQuantity();
            if (line.getTotalPrice() != null) {
                amount = amount.add(line.getTotalPrice());
            }
        }
        cart.setTotalItems(items);
        cart.setTotalAmount(amount);
        cartRepository.save(cart);
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

    /**
     * Deletes every item AND zeroes the parent Cart's own totalItems/
     * totalAmount columns - those are denormalized (recalculated and saved
     * by CartService.recalculateAndSave on every add/update/remove) rather
     * than derived live from the items list, and this method previously
     * only ever deleted the CartItem rows. That left a cleared cart (after
     * checkout, or this method's own /cart-items/cart/{id} admin endpoint)
     * with an empty items list but stale non-zero totals still on the Cart
     * row itself - exactly what CartResponse.from() then serialized back
     * out, showing "2 items, ₹60" on the cart badge/summary bar for a cart
     * that GET /api/cart/my-cart's own items array confirms is empty.
     */
    @Transactional
    public void clearCart(Long cartId) {
        // One DELETE, not a SELECT followed by one DELETE per row. The
        // previous version loaded every CartItem into the persistence
        // context purely to hand it to deleteAll() - nothing read those
        // entities. On the checkout path that happened while inventory row
        // locks were held, so every one of those round trips extended the
        // window other customers' checkouts could be blocked for.
        repository.deleteByCartId(cartId);

        // The Cart's own totals are denormalized (recalculated and saved by
        // CartService.recalculateAndSave on every add/update/remove) rather
        // than derived live from the items list. Zeroing them here is what
        // stops a cleared cart still reporting "2 items, Rs 60" on the cart
        // badge while its items array is empty.
        //
        // Left as a managed-entity update rather than a bulk UPDATE: it is a
        // single row, and going through the entity keeps it consistent with
        // how every other cart mutation writes these fields.
        Cart cart = cartRepository.findById(cartId).orElse(null);
        if (cart != null) {
            cart.setTotalItems(0);
            cart.setTotalAmount(BigDecimal.ZERO);
            cartRepository.save(cart);
        }
    }

    // Unused by the current frontend (confirmed) and admin-only, but was a
    // plain findAll() - every cart item across every customer's cart, ever.
    // Capped defensively rather than left as live unbounded API surface.
    private static final int ADMIN_LIST_CAP = 500;

    public List<CartItem> getAll() {
        return repository.findAll(org.springframework.data.domain.PageRequest.of(0, ADMIN_LIST_CAP)).getContent();
    }

    public List<CartItem> getCartItems(Long cartId) {
        return repository.findByCartId(cartId);
    }

    /**
     * Cart items with variant, product and category already fetched - for
     * the two callers that read all three per line (checkout preview and
     * place order). See CartItemRepository.findByCartIdForCheckout for the
     * measured cost of not doing this.
     */
    public List<CartItem> getCartItemsForCheckout(Long cartId) {
        return repository.findByCartIdForCheckout(cartId);
    }

    public boolean isCartEmpty(Long cartId) {
        return repository.findByCartId(cartId).isEmpty();
    }
}