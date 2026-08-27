package com.gpstore.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductBrowseRepositorySearchTest {

    @Test
    void likePatternEscapesWildcardCharacters() {
        assertEquals("%rice%", ProductBrowseRepository.toLikePattern("rice"));
        assertEquals("%100#%%", ProductBrowseRepository.toLikePattern("100%"));
        assertEquals("%a#_b%", ProductBrowseRepository.toLikePattern("a_b"));
        assertEquals("%##tag%", ProductBrowseRepository.toLikePattern("#tag"));
    }

    @Test
    void assembledSqlKeepsSpaceBeforeExists() {
        String trigram = ProductBrowseRepository.trigramSqlForTest();
        String ilike = ProductBrowseRepository.ilikeSqlForTest();
        assertTrue(trigram.contains("AND EXISTS"), trigram);
        assertTrue(ilike.contains("AND EXISTS"), ilike);
        org.junit.jupiter.api.Assertions.assertFalse(trigram.contains("ANDEXISTS"));
        org.junit.jupiter.api.Assertions.assertFalse(ilike.contains("ANDEXISTS"));
    }
}
