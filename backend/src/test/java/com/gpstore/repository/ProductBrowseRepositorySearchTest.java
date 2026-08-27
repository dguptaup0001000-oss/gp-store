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
    void missingTrigramIsDetectedFromPostgresMessages() {
        RuntimeException error = new RuntimeException(
                "could not extract ResultSet",
                new RuntimeException("ERROR: operator does not exist: character varying % character varying"));
        assertTrue(ProductBrowseRepository.looksLikeMissingTrigram(error));
    }
}
