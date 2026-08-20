package com.gpstore.repository;

import com.gpstore.entity.SearchSynonym;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchSynonymRepository extends JpaRepository<SearchSynonym, Long> {

    List<SearchSynonym> findByActiveTrue();
}
