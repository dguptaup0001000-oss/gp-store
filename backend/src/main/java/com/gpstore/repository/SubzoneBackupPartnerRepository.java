package com.gpstore.repository;

import com.gpstore.entity.SubzoneBackupPartner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubzoneBackupPartnerRepository extends JpaRepository<SubzoneBackupPartner, Long> {

    /** Priority order, ties broken by id so the sequence is always total. */
    List<SubzoneBackupPartner> findBySubzoneIdOrderByPriorityAscIdAsc(Long subzoneId);

    void deleteBySubzoneId(Long subzoneId);
}
