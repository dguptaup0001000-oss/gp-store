package com.gpstore.service;

import com.gpstore.entity.OrderItem;
import com.gpstore.repository.OrderItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderItemService {

    private final OrderItemRepository orderItemRepository;

    public OrderItemService(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    public OrderItem saveOrderItem(OrderItem orderItem) {
        return orderItemRepository.save(orderItem);
    }

    // Unused by the current frontend (confirmed) and admin-only, but was a
    // plain findAll() - every line item of every order ever placed. Capped
    // defensively rather than left as live unbounded API surface.
    private static final int ADMIN_LIST_CAP = 500;

    public List<OrderItem> getAllOrderItems() {
        return orderItemRepository.findAll(org.springframework.data.domain.PageRequest.of(0, ADMIN_LIST_CAP)).getContent();
    }
}