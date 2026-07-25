package com.gpstore.repository;

import com.gpstore.entity.Wishlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    List<Wishlist> findByCustomerId(Long customerId);

    Optional<Wishlist> findByIdAndCustomerId(Long id, Long customerId);
}
