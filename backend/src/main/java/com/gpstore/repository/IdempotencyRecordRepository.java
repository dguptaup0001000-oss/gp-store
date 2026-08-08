package com.gpstore.repository;

import com.gpstore.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);
}
