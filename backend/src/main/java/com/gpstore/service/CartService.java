package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.CartItem;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.TenantContext;

import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.repository.CartItemRepository;

import com.gpstore.entity.Cart;
import com.gpstore.entity.Inventory;
import com.gpstore.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository productVariantRepository;
    private final CartItemRepository cartItemRepository;
    private final InventoryRepository inventoryRepository;
    private final com.gpstore.catalog.shop.ShopCatalog shopCatalog;
    private final com.gpstore.catalog.shop.ShopProductVariantRepository shopListings;

    public CartService(
            CartRepository cartRepository,
            CustomerRepository customerRepository,
            ProductVariantRepository productVariantRepository,
            CartItemRepository cartItemRepository,
            InventoryRepository inventoryRepository,
            com.gpstore.catalog.shop.ShopCatalog shopCatalog,
            com.gpstore.catalog.shop.ShopProductVariantRepository shopListings) {

        this.cartRepository = cartRepository;
        this.customerRepository = customerRepository;
        this.productVariantRepository = productVariantRepository;
        this.cartItemRepository = cartItemRepository;
        this.inventoryRepository = inventoryRepository;
        this.shopCatalog = shopCatalog;
        this.shopListings = shopListings;
    }

    public Cart saveCart(Cart cart) {
        return cartRepository.save(cart);
    }

    /**
     * Admin cart-abandonment listing, PAGED - never findAll().
     *
     * Same reasoning as AddressService.getAll: every cart in the shop in one
     * response is an unbounded query whose cost grows with the customer base,
     * and carts are worse than addresses because each one drags its items
     * along. Sorted by id so paging is stable.
     */
    public org.springframework.data.domain.Page<Cart> getAllCarts(
            org.springframework.data.domain.Pageable pageable) {
        return cartRepository.findAll(pageable);
    }

    // ------------------------------------------------------------------
    // DTOs, produced inside the transaction
    //
    // A CartResponse names each line's product, which is two lazy
    // associations away (CartItem -> ProductVariant -> Product). While the
    // controller did that mapping, the loads happened during response
    // serialisation and only worked because open-session-in-view was holding
    // a pooled database connection open for the length of the whole request.
    // With that off (see spring.jpa.open-in-view) the same code raises
    // LazyInitializationException - which is the accurate report of what was
    // always going on. Mapping here is the fix, and it is also faster: the
    // loads happen against an open session in a bounded number of batched
    // queries rather than one per row while Jackson writes.
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.gpstore.dto.response.CartResponse> getAllCartResponses(
            org.springframework.data.domain.Pageable pageable) {
        // DELIBERATELY NOT a fetch join on the items collection. Paginating a
        // query that fetch-joins a one-to-many forces Hibernate to read every
        // matching row and page in memory - which on the admin listing of
        // every cart in the shop is the exact unbounded query the paging was
        // added to prevent. hibernate.default_batch_fetch_size (16) makes the
        // association loads batched instead of one-per-row.
        return cartRepository.findAll(pageable).map(com.gpstore.dto.response.CartResponse::from);
    }

    public Cart getCustomerCart(Long customerId) {
        return cartRepository.findByCustomerIdWithItemsFetched(customerId).orElse(null);
    }

    /**
     * The customer's own cart, mapped while the session is open.
     *
     * A customer with no cart row still gets a CartResponse - an empty one,
     * from CartResponse.from(null). That is the existing contract and the app
     * relies on it: returning null here would send a bare "null" body to a
     * client that expects an object with an items array.
     */
    @Transactional(readOnly = true)
    public com.gpstore.dto.response.CartResponse getCustomerCartResponse(Long customerId) {
        return toCustomerResponse(getCustomerCart(customerId));
    }

    /**
     * The three mutations, mapped inside their own transaction.
     *
     * The mutation methods below are already @Transactional; what these add
     * is that the DTO is built before that transaction closes, rather than
     * afterwards in the controller. Same rule as the reads above, same
     * reason.
     *
     * @Transactional here rather than relying on the inner annotation: this
     * is a self-invocation, so the inner @Transactional would not be applied
     * by the proxy at all.
     */
    @Transactional
    public com.gpstore.dto.response.CartResponse addToCartResponse(
            Long customerId, Long variantId, Integer quantity) {
        return toCustomerResponse(addToCart(customerId, variantId, quantity));
    }

    @Transactional
    public com.gpstore.dto.response.CartResponse updateItemQuantityResponse(
            Long customerId, Long cartItemId, int newQuantity) {
        return toCustomerResponse(updateItemQuantity(customerId, cartItemId, newQuantity));
    }

    @Transactional
    public com.gpstore.dto.response.CartResponse removeItemResponse(Long customerId, Long cartItemId) {
        return toCustomerResponse(removeItem(customerId, cartItemId));
    }

    @Transactional
    @io.micrometer.core.annotation.Timed(value = "cart.add", description = "Cart add mutation", percentiles = {0.5, 0.95, 0.99})
    public Cart addToCart(Long customerId, Long variantId, Integer quantity) {

        if (quantity == null || quantity <= 0) {
            throw new BadRequestException("Quantity must be positive");
        }

        // ---- BEFORE THE LOCK ---------------------------------------------
        //
        // THE ORDER OF THESE TWO BLOCKS IS THE FIX, and it is worth spelling
        // out because the code reads almost identically either way.
        //
        // Reading the variant and checking whether it is sellable needs no
        // lock at all - it touches nothing this customer can race with. It
        // used to happen AFTER the customer row was locked, which meant every
        // add-to-cart held that lock across an extra database round trip (two,
        // before the fetch join below), while every other cart request for the
        // same customer queued behind it holding a pooled connection of its
        // own and doing nothing.
        //
        // That is what "connections stay active too long" looks like in
        // production: the connections are not working, they are waiting on a
        // row lock, and the pool queue behind them is what shows up as
        // `waiting=27`. Moving read-only work out of the critical section
        // shortens the queue without weakening a single guarantee.
        //
        // findByIdWithProduct, not findById: the availability check below
        // reads the product, which is a lazy association and therefore a
        // second round trip that used to happen inside the lock too.
        ProductVariant variant = productVariantRepository.findByIdWithProduct(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));

        // Same check as checkout - catching it here means the customer
        // finds out immediately when they try to add it, not later at
        // checkout after it's been sitting in their cart.
        if (variant.getAvailable() == null || !variant.getAvailable()
                || variant.getProduct() == null
                || variant.getProduct().getActive() == null
                || !variant.getProduct().getActive()) {
            throw new ConflictException(
                    (variant.getProduct() != null ? variant.getProduct().getName() : "This item")
                            + " is currently unavailable");
        }

        // WHAT THIS SHOP CHARGES, not what the catalogue suggests. The
        // catalogue row is the price a shop starts from; the shop's own
        // listing is the price a customer pays, and a shop that does not list
        // the item does not sell it at all. Under one shop the two are equal
        // by construction, so nothing about this changes today.
        BigDecimal unitPrice = shopCatalog.priceOf(variant).orElseThrow(() -> new ConflictException(
                (variant.getProduct() != null ? variant.getProduct().getName() : "This item")
                        + " is currently unavailable"));

        // ---- THE CRITICAL SECTION STARTS HERE ----------------------------
        //
        // Locked for the rest of this transaction (see
        // CustomerRepository.findByIdForUpdate) - a second concurrent
        // addToCart/updateItemQuantity/removeItem for this same customer
        // blocks here until this one commits, instead of racing the cart
        // lookup-or-create and item quantity increment below. The customer
        // row (unlike the cart row) is guaranteed to already exist, so this
        // also closes the "this customer's very first cart, created twice
        // at once" race that locking the cart row alone couldn't.
        //
        // UNCHANGED IN SUBSTANCE. Everything that races is still inside it,
        // in the same order, with the same lock. What moved out above is only
        // work that never needed to be in here.
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Cart cart = cartRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setCustomer(customer);
                    return cartRepository.save(newCart);
                });

        CartItem existingItem = cartItemRepository
                .findByCartIdAndProductVariantId(cart.getId(), variant.getId())
                .orElse(null);

        int nextQuantity = (existingItem != null ? existingItem.getQuantity() : 0) + quantity;
        requireStockFor(variant.getId(), nextQuantity, variant.getProduct() != null
                ? variant.getProduct().getName() : "This item");

        // WHICH SHELF THIS CAME OFF, from the shop the request resolved to and
        // from nothing the caller sent. Checkout groups the basket by it, so a
        // value a client could choose would be a value that moves an item into
        // another shop's order.
        //
        // Null when there is no scope at all - a background job, a test
        // building a basket directly - and CartItem's own @PrePersist then
        // applies the same rule every other shop-stamped row gets. Nothing
        // reaches the database unstamped either way.
        Long shelfShopId = TenantContext.isSet() && TenantContext.current().isSingleShop()
                ? TenantContext.current().shopId()
                : null;

        if (existingItem != null) {
            existingItem.setQuantity(nextQuantity);
            if (shelfShopId != null) {
                existingItem.setShopId(shelfShopId);
            }
            existingItem.setPrice(unitPrice);
            existingItem.setTotalPrice(unitPrice.multiply(BigDecimal.valueOf(nextQuantity)));
            cartItemRepository.save(existingItem);
        } else {
            CartItem cartItem = new CartItem();
            cartItem.setCart(cart);
            cartItem.setProductVariant(variant);
            cartItem.setQuantity(quantity);
            cartItem.setShopId(shelfShopId);
            cartItem.setPrice(unitPrice);
            cartItem.setTotalPrice(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            cartItemRepository.save(cartItem);
            cart.getItems().add(cartItem);
        }

        recalculateAndSave(cart);
        return fetchWithItems(customerId);
    }

    /**
     * Sets the EXACT quantity (not additive, unlike addToCart) - this is
     * what the spec's "quantity increase/decrease" controls actually need,
     * since a +/- stepper wants to set an absolute new value, not add a
     * delta each tap. Setting quantity to 0 or less removes the item
     * entirely - matches how every real cart UI treats "decrease to zero".
     * Ownership is verified via the cart's customerId, not just the item ID -
     * this is what stops one customer from editing another's cart item by ID.
     */
    @Transactional
    @io.micrometer.core.annotation.Timed(value = "cart.update_quantity", description = "Cart quantity mutation", percentiles = {0.5, 0.95, 0.99})
    public Cart updateItemQuantity(Long customerId, Long cartItemId, int newQuantity) {
        // See CustomerRepository.findByIdForUpdate's doc comment - locks out
        // any concurrent addToCart/updateItemQuantity/removeItem for this
        // customer until this transaction commits.
        customerRepository.findByIdForUpdate(customerId);

        CartItem item = getOwnedCartItem(customerId, cartItemId);
        Cart cart = item.getCart();

        if (newQuantity <= 0) {
            cart.getItems().remove(item);
            cartItemRepository.delete(item);
        } else {
            var variant = item.getProductVariant();
            requireStockFor(variant.getId(), newQuantity, variant.getProduct() != null
                    ? variant.getProduct().getName() : "This item");
            shopCatalog.priceOf(variant).ifPresent(item::setPrice);
            item.setQuantity(newQuantity);
            item.setTotalPrice(item.getPrice().multiply(BigDecimal.valueOf(newQuantity)));
            cartItemRepository.save(item);
        }

        recalculateAndSave(cart);
        return fetchWithItems(customerId);
    }

    @Transactional
    @io.micrometer.core.annotation.Timed(value = "cart.remove", description = "Cart remove mutation", percentiles = {0.5, 0.95, 0.99})
    public Cart removeItem(Long customerId, Long cartItemId) {
        // See CustomerRepository.findByIdForUpdate's doc comment.
        customerRepository.findByIdForUpdate(customerId);

        CartItem item = getOwnedCartItem(customerId, cartItemId);
        Cart cart = item.getCart();

        cart.getItems().remove(item);
        cartItemRepository.delete(item);

        recalculateAndSave(cart);
        return fetchWithItems(customerId);
    }

    /** See CartRepository.findByCustomerIdWithItemsFetched's doc comment - avoids N+1 on the response conversion. */
    private Cart fetchWithItems(Long customerId) {
        return cartRepository.findByCustomerIdWithItemsFetched(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found"));
    }

    /** Throws if the item doesn't exist or belongs to a different customer's cart - closes the IDOR. */
    private CartItem getOwnedCartItem(Long customerId, Long cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));

        if (item.getCart() == null || item.getCart().getCustomer() == null
                || !item.getCart().getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Cart item not found");
        }

        return item;
    }

    private com.gpstore.dto.response.CartResponse toCustomerResponse(Cart cart) {
        if (cart == null) {
            return com.gpstore.dto.response.CartResponse.from(null);
        }
        java.util.LinkedHashSet<Long> variantIds = new java.util.LinkedHashSet<>();
        for (CartItem item : cart.getItems()) {
            if (item.getProductVariant() == null || item.getProductVariant().getId() == null) {
                continue;
            }
            variantIds.add(item.getProductVariant().getId());
        }
        java.util.Map<Long, Integer> stock = new java.util.HashMap<>();
        java.util.Map<Long, BigDecimal> shopPrices = new java.util.HashMap<>();
        if (!variantIds.isEmpty()) {
            // ONE query for stock AND this shop's price - see
            // ShopProductVariantRepository.findShelfLines on why they are
            // fetched together rather than in two round trips.
            for (var line : shopListings.findShelfLines(variantIds)) {
                stock.put(line.getVariantId(), line.getStock() == null ? 0 : line.getStock());
                if (line.getPrice() != null) {
                    shopPrices.put(line.getVariantId(), line.getPrice());
                }
            }
            for (Long variantId : variantIds) {
                stock.putIfAbsent(variantId, 0);
            }
        }

        return com.gpstore.dto.response.CartResponse.from(cart, stock, shopPrices);
    }

    private void requireStockFor(Long variantId, int quantity, String productName) {
        int stock = inventoryRepository.findByProductVariantId(variantId)
                .map(row -> row.getStock() == null ? 0 : row.getStock())
                .orElse(0);
        if (stock < quantity) {
            throw new ConflictException(
                    stock <= 0
                            ? productName + " is out of stock."
                            : "Only " + stock + " left of " + productName + ".");
        }
    }

    private Cart recalculateAndSave(Cart cart) {
        cart.setTotalItems(cart.getItems().stream().mapToInt(CartItem::getQuantity).sum());
        cart.setTotalAmount(cart.getItems().stream()
                .map(CartItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return cartRepository.save(cart);
    }
}
