package com.gpstore.repository;

import com.gpstore.entity.R2StagingObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface R2StagingObjectRepository extends JpaRepository<R2StagingObject, String> {

    List<R2StagingObject> findByCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff, Pageable pageable);
}
