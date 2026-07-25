package com.gpstore.repository;

import java.util.Optional;
import com.gpstore.entity.ProductVariant;

import com.gpstore.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCartId(Long cartId);
    Optional<CartItem> findByCartIdAndProductVariantId(
        Long cartId,
        Long productVariantId
);

}