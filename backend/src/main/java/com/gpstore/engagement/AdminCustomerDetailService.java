package com.gpstore.engagement;

import com.gpstore.dto.response.AdminCustomerDetailResponse;
import com.gpstore.dto.response.AdminCustomerDetailResponse.*;
import com.gpstore.entity.*;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.*;
import com.gpstore.upload.CatalogImageDelivery;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the one screen a shopkeeper opens to answer "who is this?".
 *
 * READ-ONLY, AND THAT IS LOAD-BEARING. Reading a customer must not change
 * anything about them. This service was written after a bug elsewhere in this
 * codebase where a GET lazily created and saved a settings row, which then
 * changed what a later test computed - a read that writes is a read that
 * surprises somebody eventually.
 *
 * EVERY LOOKUP IS BOUNDED. A customer with four hundred orders and a
 * three-year wishlist would otherwise turn one screen into the largest query
 * the shop runs. The totals are aggregates; the lists are capped and say so.
 */
@Service
public class AdminCustomerDetailService {

    /** Enough to recognise a shopper's habits; not their archive. */
    private static final int MAX_WISHLIST_SHOWN = 50;
    private static final int MAX_CART_LINES_SHOWN = 50;

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final CartRepository cartRepository;
    private final WishlistRepository wishlistRepository;
    private final OrderRepository orderRepository;
    private final CustomerAppSessionRepository sessionRepository;
    private final CustomerDeliveryRatingRepository ratingRepository;

    public AdminCustomerDetailService(CustomerRepository customerRepository,
                                      AddressRepository addressRepository,
                                      CartRepository cartRepository,
                                      WishlistRepository wishlistRepository,
                                      OrderRepository orderRepository,
                                      CustomerAppSessionRepository sessionRepository,
                                      CustomerDeliveryRatingRepository ratingRepository) {
        this.customerRepository = customerRepository;
        this.addressRepository = addressRepository;
        this.cartRepository = cartRepository;
        this.wishlistRepository = wishlistRepository;
        this.orderRepository = orderRepository;
        this.sessionRepository = sessionRepository;
        this.ratingRepository = ratingRepository;
    }

    @Transactional(readOnly = true)
    public AdminCustomerDetailResponse of(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        return new AdminCustomerDetailResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail(),
                customer.getMobileNumber(),
                customer.getRole() == null ? null : customer.getRole().name(),
                customer.getActive(),
                customer.getVerified(),
                customer.getProfileImageUrl() == null
                        ? null
                        : CatalogImageDelivery.forClient(customer.getProfileImageUrl()),
                addressesOf(customerId),
                cartOf(customerId),
                wishlistOf(customerId),
                orderStatsOf(customerId),
                engagementOf(customerId),
                conductOf(customerId)
        );
    }

    // ----------------------------------------------------------- addresses

    private List<AddressLine> addressesOf(Long customerId) {
        List<AddressLine> lines = new ArrayList<>();
        for (Address address : addressRepository.findByCustomerId(customerId)) {
            lines.add(new AddressLine(
                    address.getId(),
                    address.getLabel(),
                    address.getFullName(),
                    address.getMobileNumber(),
                    readableAddress(address),
                    address.getLandmark(),
                    address.getDeliveryInstructions(),
                    address.getPincode(),
                    address.getDefaultAddress(),
                    address.getLatitude() != null && address.getLongitude() != null));
        }
        return lines;
    }

    /**
     * The address as a person would say it, not as the columns store it.
     *
     * COORDINATES ARE DELIBERATELY NOT HERE. Whether a pin exists is useful -
     * an address with no pin cannot be priced or routed. The pin ITSELF is a
     * customer's home to five decimal places, and a screen that displays it
     * puts it in a screenshot, a support chat and a photo of a monitor. The
     * boolean answers the operational question without carrying that.
     */
    private static String readableAddress(Address address) {
        StringBuilder out = new StringBuilder();
        appendIfPresent(out, address.getHouseNo());
        appendIfPresent(out, address.getBuildingName());
        appendIfPresent(out, address.getStreet());
        appendIfPresent(out, address.getArea());
        appendIfPresent(out, address.getCity());
        appendIfPresent(out, address.getDistrict());
        appendIfPresent(out, address.getState());
        return out.toString();
    }

    private static void appendIfPresent(StringBuilder out, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (out.length() > 0) {
            out.append(", ");
        }
        out.append(part.trim());
    }

    // ---------------------------------------------------------------- cart

    /**
     * What is sitting in their basket right now.
     *
     * WHY A SHOPKEEPER WANTS THIS. Somebody rings to ask "is my order placed?"
     * and the answer is often "no - it is still in your basket". Seeing the
     * basket turns that call into one sentence instead of a guess.
     */
    private CartSummary cartOf(Long customerId) {
        Cart cart = cartRepository.findByCustomerIdWithItemsFetched(customerId).orElse(null);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return new CartSummary(0, BigDecimal.ZERO, List.of());
        }

        List<CartLine> lines = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            if (lines.size() >= MAX_CART_LINES_SHOWN) {
                break;
            }
            ProductVariant variant = item.getProductVariant();
            lines.add(new CartLine(
                    productNameOf(variant),
                    packOf(variant),
                    item.getQuantity(),
                    item.getTotalPrice(),
                    photoOf(variant)));
        }

        return new CartSummary(
                cart.getTotalItems(),
                cart.getTotalAmount() == null ? BigDecimal.ZERO : cart.getTotalAmount(),
                lines);
    }

    // ------------------------------------------------------------ wishlist

    private List<WishlistLine> wishlistOf(Long customerId) {
        List<WishlistLine> lines = new ArrayList<>();
        for (Wishlist entry : wishlistRepository.findByCustomerId(customerId)) {
            if (lines.size() >= MAX_WISHLIST_SHOWN) {
                break;
            }
            Product product = entry.getProduct();
            if (product == null) {
                continue;
            }
            lines.add(new WishlistLine(
                    product.getId(),
                    // Staff see the real name. A privacy-flagged product is
                    // renamed for customers, but the shop fulfilling and
                    // advising on it needs to know what it actually is.
                    product.getName(),
                    product.getBrand(),
                    coverPhotoOf(product)));
        }
        return lines;
    }

    // -------------------------------------------------------------- orders

    /**
     * Counted in Java rather than by five aggregate queries.
     *
     * A kirana customer has tens of orders, not millions, and one fetch that
     * the address and cart lookups have already warmed beats five round trips
     * to compute numbers off the same rows. If a customer ever does have
     * thousands, this is the line to move into SQL.
     */
    private OrderStats orderStatsOf(Long customerId) {
        List<Order> orders = orderRepository.findByCustomerIdOrderByOrderDateDesc(customerId);
        if (orders.isEmpty()) {
            return new OrderStats(0, BigDecimal.ZERO, null, null, 0);
        }

        BigDecimal spend = BigDecimal.ZERO;
        long cancelled = 0;
        LocalDateTime first = null;
        LocalDateTime last = null;

        for (Order order : orders) {
            if (order.getOrderStatus() == OrderStatus.CANCELLED) {
                cancelled++;
            } else if (order.getTotalAmount() != null) {
                // CANCELLED ORDERS ARE NOT SPEND. Counting them would tell a
                // shopkeeper somebody is a bigger customer than they are, and
                // that is the kind of wrong number that changes how a person
                // is treated at the counter.
                spend = spend.add(order.getTotalAmount());
            }

            LocalDateTime placed = order.getOrderDate();
            if (placed != null) {
                if (first == null || placed.isBefore(first)) {
                    first = placed;
                }
                if (last == null || placed.isAfter(last)) {
                    last = placed;
                }
            }
        }

        return new OrderStats(orders.size(), spend, first, last, cancelled);
    }

    // ---------------------------------------------------------- engagement

    private Engagement engagementOf(Long customerId) {
        return new Engagement(
                sessionRepository.totalSecondsFor(customerId),
                sessionRepository.sessionCountFor(customerId),
                sessionRepository.lastSeenFor(customerId));
    }

    /**
     * How riders have found this customer at the door.
     *
     * THE READ SIDE OF A FEATURE THAT ONLY HAD A WRITE SIDE. Riders have been
     * able to score a delivery out of ten since the collection screen shipped,
     * and the rows have been landing in customer_delivery_ratings ever since -
     * but nothing anywhere read them, so the shopkeeper this was built for
     * could not see a single one.
     *
     * The average is over EVERY rating; only the list is capped. Showing an
     * average of the last ten while calling it the customer's score would be
     * a different number wearing the same label.
     */
    private DeliveryConduct conductOf(Long customerId) {
        CustomerDeliveryRatingRepository.ConductSummary summary =
                ratingRepository.summaryFor(customerId);

        List<ConductLine> recent = new ArrayList<>();
        for (CustomerDeliveryRating rating
                : ratingRepository.findTop10ByCustomerIdOrderByCreatedAtDesc(customerId)) {
            recent.add(new ConductLine(
                    rating.getOrderId(), rating.getScore(), rating.getCreatedAt()));
        }

        // Null average and zero count for somebody nobody has rated - never a
        // zero score, which reads as the worst possible customer rather than
        // as "we do not know".
        return new DeliveryConduct(
                summary == null ? null : summary.getAverage(),
                summary == null ? 0L : summary.getTotal(),
                recent);
    }

    // ------------------------------------------------------------- helpers

    private static String productNameOf(ProductVariant variant) {
        if (variant == null) {
            return "Item";
        }
        if (variant.getProduct() != null && variant.getProduct().getName() != null) {
            return variant.getProduct().getName();
        }
        return variant.getSku() == null ? "Item" : variant.getSku();
    }

    private static String packOf(ProductVariant variant) {
        if (variant == null || variant.getQuantity() == null || variant.getUnit() == null) {
            return null;
        }
        double quantity = variant.getQuantity();
        String number = quantity == Math.rint(quantity)
                ? String.valueOf((long) quantity)
                : String.valueOf(quantity);
        return number + " " + variant.getUnit();
    }

    /**
     * A wishlisted product's picture, taken from its variants.
     *
     * PRODUCT HAS NO PICTURE OF ITS OWN in this schema - only variants carry
     * imageUrl - so the cover has to be borrowed from one. Active variants
     * win, because a discontinued pack size is not what the shop would show.
     *
     * No extra query: WishlistRepository.findByCustomerId already
     * left-join-fetches p.variants, so this walks a collection that is
     * already in memory. Reading it through a lazy proxy instead would be one
     * SELECT per wishlisted product.
     */
    private static String coverPhotoOf(Product product) {
        if (product.getVariants() == null) {
            return null;
        }
        String fallback = null;
        for (ProductVariant variant : product.getVariants()) {
            if (variant == null || variant.getImageUrl() == null || variant.getImageUrl().isBlank()) {
                continue;
            }
            if (Boolean.TRUE.equals(variant.getActive())) {
                return CatalogImageDelivery.forClient(variant.getImageUrl());
            }
            if (fallback == null) {
                fallback = variant.getImageUrl();
            }
        }
        return fallback == null ? null : CatalogImageDelivery.forClient(fallback);
    }

    /** Signed on the way out. A stored delivery URL expires within the hour. */
    private static String photoOf(ProductVariant variant) {
        if (variant == null || variant.getImageUrl() == null || variant.getImageUrl().isBlank()) {
            return null;
        }
        return CatalogImageDelivery.forClient(variant.getImageUrl());
    }
}
