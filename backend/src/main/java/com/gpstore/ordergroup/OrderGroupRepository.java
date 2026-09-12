package com.gpstore.ordergroup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderGroupRepository extends JpaRepository<OrderGroup, Long> {

    Optional<OrderGroup> findByGroupNumber(String groupNumber);

    /**
     * A customer's own checkouts.
     *
     * NOT FILTERED BY SHOP, because a group spans them - and not a leak,
     * because every row it returns belongs to the customer asking. That is
     * the other axis of protection this codebase has always had: the tenant
     * filter keeps merchants apart, and the ownership check keeps customers
     * apart.
     */
    List<OrderGroup> findByCustomerIdOrderByIdDesc(Long customerId);
}
