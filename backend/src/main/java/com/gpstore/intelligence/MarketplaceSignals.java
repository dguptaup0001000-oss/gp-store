package com.gpstore.intelligence;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Writes privacy-minimised discovery signals. Coordinates are reduced to an
 * approximately kilometre-scale cell and raw events are never merchant-readable.
 */
@Service
public class MarketplaceSignals {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MarketplaceSignals.class);
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public MarketplaceSignals(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void search(String query, Double latitude, Double longitude,
                       Set<CommerceMode> modes, int resultCount) {
        if (query == null || query.isBlank()) return;
        String clean = query.trim().replaceAll("\\s+", " ");
        if (clean.length() > 240) clean = clean.substring(0, 240);
        Long customer = null;
        try {
            customer = currentUser.customerId();
        } catch (RuntimeException ignored) {
            // Anonymous discovery remains useful; no pseudonymous identifier is invented.
        }
        String mode = modes != null && modes.size() == 1 ? modes.iterator().next().name() : null;
        try {
            jdbc.update("""
                    INSERT INTO marketplace_search_events
                        (customer_id, query_text, normalized_query, commerce_mode,
                         latitude_cell, longitude_cell, result_count)
                    VALUES (?, ?, lower(?), ?, ?, ?, ?)
                    """, customer, clean, clean, mode, cell(latitude), cell(longitude),
                    Math.max(0, resultCount));
        } catch (RuntimeException unavailable) {
            // Analytics may lose one event; discovery must never lose its answer.
            log.warn("Could not record marketplace search signal: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    private static BigDecimal cell(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
