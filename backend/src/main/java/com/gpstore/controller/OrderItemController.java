package com.gpstore.controller;

import com.gpstore.entity.OrderItem;
import com.gpstore.service.OrderItemService;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/order-items")
public class OrderItemController {

    private final OrderItemService orderItemService;

    public OrderItemController(OrderItemService orderItemService) {
        this.orderItemService = orderItemService;
    }

    @PostMapping
    public OrderItem createOrderItem(@RequestBody OrderItem orderItem) {
        return orderItemService.saveOrderItem(orderItem);
    }

    // GET /api/order-items IS GONE, and it was never usable.
    //
    // It returned every OrderItem in the system as a raw entity. Raw entities
    // carry Hibernate lazy proxies, and Jackson cannot serialise one:
    //
    //     HttpMessageConversionException: Type definition error:
    //     [simple type, class org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor]
    //
    // so it answered 500 on every call it ever received. Nothing in the app
    // referenced it - order items reach clients through OrderDetailResponse -
    // and had it worked it would have loaded every order item ever created
    // into memory unpaginated, which this codebase forbids everywhere else.
    // Deleting it fixes the 500 and removes an unbounded query in one go.
}