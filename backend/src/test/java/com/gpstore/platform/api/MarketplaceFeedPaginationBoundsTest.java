package com.gpstore.platform.api;

import com.gpstore.catalog.shop.MarketplaceFeedRepository;
import com.gpstore.intelligence.MarketplaceSignals;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.search.SynonymDictionary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MarketplaceFeedPaginationBoundsTest {

    @Mock private ShopDiscovery discovery;
    @Mock private MarketplaceFeedRepository feed;
    @Mock private MarketplaceSignals signals;
    @Mock private SynonymDictionary synonyms;

    private MarketplaceFeedService service;

    @BeforeEach
    void setUp() {
        service = new MarketplaceFeedService(discovery, feed, signals, synonyms);
    }

    @Test
    void feedRejectsAnOverflowingOffsetBeforeReadingShopsOrProducts() {
        assertTrue(service.page(26.0, 83.0, Set.of(), null, Integer.MAX_VALUE, 20).isEmpty());

        verifyNoInteractions(discovery, feed, signals, synonyms);
    }

    @Test
    void searchRejectsAnOverflowingOffsetBeforeReadingShopsOrProducts() {
        assertTrue(service.search("rice", 26.0, 83.0, Set.of(), Integer.MAX_VALUE, 20).isEmpty());

        verifyNoInteractions(discovery, feed, signals, synonyms);
    }

    @Test
    void feedBoundsLargeButNonOverflowingOffsetsBeforeDatabaseWork() {
        // 2,001 * 50 is representable as an int, but an OFFSET of 100,050
        // would still force PostgreSQL to walk an unnecessarily deep prefix.
        assertTrue(service.page(26.0, 83.0, Set.of(), null, 2_001, 50).isEmpty());

        verifyNoInteractions(discovery, feed, signals, synonyms);
    }
}
