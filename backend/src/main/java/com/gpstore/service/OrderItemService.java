package com.gpstore.service;

import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderItemService {

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final ProductVariantRepository productVariantRepository;
    private final TaxService taxService;

    public OrderItemService(OrderItemRepository orderItemRepository,
                            OrderRepository orderRepository,
                            ProductVariantRepository productVariantRepository,
                            TaxService taxService) {
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.productVariantRepository = productVariantRepository;
        this.taxService = taxService;
    }

    /**
     * Adds a line to an existing order, priced by the shop.
     *
     * WHAT THIS USED TO BE: {@code orderItemRepository.save(orderItem)} on an
     * OrderItem deserialised straight from the request body. The caller chose
     * the parent order, the unit price and the line total; nothing was
     * validated and the order's own total was never touched. A line added
     * that way made the order's total disagree with the sum of its lines,
     * and the price could be anything - a rupee, or a paisa.
     *
     * THE PRICE IS THE SHOP'S, NOT THE CALLER'S. Whatever price and
     * totalPrice arrive in the body are discarded and recomputed from the
     * variant's selling price, because the amount is the shop's money and the
     * body is not evidence. Same rule the checkout follows, and the same
     * numbers: quantity times selling price, GST resolved by TaxService.
     *
     * THE ORDER'S TOTAL MOVES WITH IT. Adding merchandise increases the total
     * by exactly that line's value; the delivery fee and any discount already
     * applied are unaffected, so no checkout arithmetic has to be repeated
     * here. Both writes happen in one transaction - a line without its total,
     * or a total without its line, is worse than neither.
     *
     * A CANCELLED ORDER IS REFUSED. Its money has already been settled or
     * sent back, and quietly enlarging it would change what a customer owes
     * after the fact.
     */
    @Transactional
    public OrderItem saveOrderItem(OrderItem orderItem) {
        if (orderItem == null || orderItem.getOrder() == null || orderItem.getOrder().getId() == null) {
            throw new BadRequestException("An order line has to say which order it belongs to.");
        }
        if (orderItem.getProductVariant() == null || orderItem.getProductVariant().getId() == null) {
            throw new BadRequestException("An order line has to say which product it is.");
        }
        Integer quantity = orderItem.getQuantity();
        if (quantity == null || quantity <= 0) {
            throw new BadRequestException("An order line needs a quantity of at least one.");
        }

        // Locked, because the order's total is read and written below and a
        // concurrent line addition would otherwise lose one of the two.
        Order order = orderRepository.findByIdForUpdate(orderItem.getOrder().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getOrderStatus() == com.gpstore.enums.OrderStatus.CANCELLED) {
            throw new com.gpstore.exception.ConflictException(
                    "This order is cancelled - its money is already settled.");
        }

        ProductVariant variant = productVariantRepository.findById(orderItem.getProductVariant().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        BigDecimal unitPrice = variant.getSellingPrice();
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("That product has no price to charge.");
        }
        BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

        OrderItem line = new OrderItem();
        line.setOrder(order);
        line.setProductVariant(variant);
        line.setQuantity(quantity);
        line.setPrice(unitPrice);
        line.setTotalPrice(lineTotal);
        line.setGstRate(taxService.resolveGstRate(variant));
        line.setActive(true);

        OrderItem saved = orderItemRepository.save(line);

        BigDecimal previous = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
        order.setTotalAmount(previous.add(lineTotal));
        orderRepository.save(order);

        return saved;
    }

    // Unused by the current frontend (confirmed) and admin-only, but was a
    // plain findAll() - every line item of every order ever placed. Capped
    // defensively rather than left as live unbounded API surface.
    private static final int ADMIN_LIST_CAP = 500;

    public List<OrderItem> getAllOrderItems() {
        return orderItemRepository.findAll(org.springframework.data.domain.PageRequest.of(0, ADMIN_LIST_CAP)).getContent();
    }
}
