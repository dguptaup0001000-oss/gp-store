package com.gpstore.repository;

import com.gpstore.entity.OpsBackupRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OpsBackupRunRepository extends JpaRepository<OpsBackupRun, Long> {

    Optional<OpsBackupRun> findFirstByStatusOrderByTakenAtDesc(String status);

    List<OpsBackupRun> findTop20ByOrderByTakenAtDesc();
}
