package com.gpstore.intelligence;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Writes privacy-minimised discovery signals. Coordinates are reduced to an
 * approximately kilometre-scale cell and raw events are never merchant-readable.
 */
@Service
public class MarketplaceSignals {
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 200;
    private static final String INSERT_SQL = """
            INSERT INTO marketplace_search_events
                (customer_id, query_text, normalized_query, commerce_mode,
                 latitude_cell, longitude_cell, result_count)
            VALUES (?, ?, lower(?), ?, ?, ?, ?)
            """;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MarketplaceSignals.class);
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ArrayBlockingQueue<SearchSignal> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final LongAdder dropped = new LongAdder();

    public MarketplaceSignals(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

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
        // Do not acquire a database connection on the customer request path.
        // Demand analytics is explicitly best-effort; a full bounded queue is
        // counted and dropped instead of making marketplace search wait.
        if (!pending.offer(new SearchSignal(customer, clean, mode, cell(latitude),
                cell(longitude), Math.max(0, resultCount)))) {
            dropped.increment();
            long totalDropped = dropped.sum();
            if (totalDropped % 1_000 == 0) {
                log.warn("Marketplace search analytics queue is full; dropped {} signals", totalDropped);
            }
        }
    }

    /** Flush a bounded batch away from the request thread and in one DB round trip. */
    @Scheduled(fixedDelayString = "${marketplace.analytics.flush-interval-ms:250}")
    public void flushPending() {
        List<SearchSignal> batch = new ArrayList<>(BATCH_SIZE);
        pending.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) return;
        try {
            jdbc.batchUpdate(INSERT_SQL, batch, batch.size(), (statement, signal) -> {
                statement.setObject(1, signal.customerId());
                statement.setString(2, signal.query());
                statement.setString(3, signal.query());
                statement.setString(4, signal.commerceMode());
                statement.setBigDecimal(5, signal.latitudeCell());
                statement.setBigDecimal(6, signal.longitudeCell());
                statement.setInt(7, signal.resultCount());
            });
        } catch (RuntimeException unavailable) {
            // Do not retry an uncertain JDBC batch: some drivers may have
            // committed a prefix before reporting the failure. Dropping the
            // batch avoids duplicate demand counts; discovery remains intact.
            dropped.add(batch.size());
            log.warn("Could not flush marketplace search signals (batch={}): {}",
                    batch.size(), unavailable.getClass().getSimpleName());
        }
    }

    long queuedForTests() { return pending.size(); }

    long droppedForTests() { return dropped.sum(); }

    record SearchSignal(Long customerId, String query, String commerceMode,
                        BigDecimal latitudeCell, BigDecimal longitudeCell,
                        int resultCount) {}

    private static BigDecimal cell(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
