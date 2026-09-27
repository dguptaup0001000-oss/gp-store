package com.gpstore.demand;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.CatalogUrlValidator;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.TenantContext;
import com.gpstore.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The shared demand-to-supply workflow. Eligibility is materialized at create
 * time, so merchant reads cannot widen scope or reveal a customer's pin.
 */
@Service
public class DemandNetwork {
    private static final int MAX_OPEN_PER_CUSTOMER = 5;
    private static final int MAX_RECIPIENTS = 50;
    private static final BigDecimal MAX_RADIUS_KM = new BigDecimal("50");

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ShopDiscovery discovery;

    public DemandNetwork(JdbcTemplate jdbc, CurrentUser currentUser, ShopDiscovery discovery) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.discovery = discovery;
    }

    public record CreateRequest(String description, String photoUrl, Integer quantity,
                                Long categoryId, BigDecimal budget,
                                LocalDateTime requiredBy, Double latitude, Double longitude,
                                BigDecimal radiusKm, String preferredMode) {}
    public record DemandView(Long id, String description, String photoUrl, int quantity,
                             Long categoryId, BigDecimal budget, LocalDateTime requiredBy,
                             BigDecimal radiusKm, String preferredMode, String status,
                             LocalDateTime expiresAt, LocalDateTime createdAt,
                             List<ResponseView> responses) {}
    public record ResponseRequest(String status, BigDecimal price, Integer quantity,
                                  Integer readyMinutes, String commerceMode, String note) {}
    public record ResponseView(Long id, Long shopId, String shopName, String status,
                               BigDecimal price, Integer quantity, Integer readyMinutes,
                               String commerceMode, String note, LocalDateTime createdAt) {}
    public record MerchantDemand(Long id, String description, String photoUrl, int quantity,
                                 Long categoryId, BigDecimal budget, LocalDateTime requiredBy,
                                 BigDecimal radiusKm, String preferredMode,
                                 LocalDateTime expiresAt, LocalDateTime createdAt,
                                 ResponseView myResponse) {}

    @Transactional
    public DemandView create(CreateRequest request) {
        Long customerId = currentUser.customerId();
        String description = request == null ? null : clean(request.description(), 500);
        if (description == null) throw new BadRequestException("Describe what you need.");
        if (request.latitude() == null || request.longitude() == null) {
            throw new BadRequestException("Choose an approximate search location.");
        }
        if (!Double.isFinite(request.latitude()) || !Double.isFinite(request.longitude())
                || request.latitude() < -90 || request.latitude() > 90
                || request.longitude() < -180 || request.longitude() > 180) {
            throw new BadRequestException("Search location is invalid.");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1 || quantity > 10_000) throw new BadRequestException("Quantity is invalid.");
        if (request.budget() != null && request.budget().signum() <= 0) {
            throw new BadRequestException("Budget must be greater than zero.");
        }
        BigDecimal radius = request.radiusKm() == null ? new BigDecimal("8")
                : request.radiusKm().min(MAX_RADIUS_KM);
        if (radius.signum() <= 0) throw new BadRequestException("Radius must be greater than zero.");
        CommerceMode mode = mode(request.preferredMode(), false);
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM demand_requests
                 WHERE customer_id=? AND status='OPEN' AND expires_at > now()
                """, Integer.class, customerId);
        if (open != null && open >= MAX_OPEN_PER_CUSTOMER) {
            throw new BadRequestException("Close an existing request before creating another.");
        }
        if (request.categoryId() != null && Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM categories WHERE id=? AND active=true)",
                Boolean.class, request.categoryId()))) {
            throw new BadRequestException("Choose a valid category.");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = request.requiredBy() == null
                ? now.plusDays(7) : min(request.requiredBy(), now.plusDays(30));
        if (!expires.isAfter(now)) throw new BadRequestException("Required-by time must be in the future.");
        Long id = jdbc.queryForObject("""
                INSERT INTO demand_requests
                    (customer_id, description, photo_url, quantity, category_id, budget,
                     required_by, latitude, longitude, radius_km, preferred_mode, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, customerId, description, safePhoto(request.photoUrl()), quantity,
                request.categoryId(), request.budget(), request.requiredBy() == null
                        ? null : Timestamp.valueOf(request.requiredBy()),
                coarseCoordinate(request.latitude()), coarseCoordinate(request.longitude()),
                radius, mode == null ? null : mode.name(), Timestamp.valueOf(expires));

        List<Long> candidates = discovery.shopsWithin(
                        request.latitude(), request.longitude(), radius).stream()
                .map(near -> near.shop().getId()).limit(MAX_RECIPIENTS).toList();
        if (!candidates.isEmpty()) {
            Set<Long> eligible = eligible(candidates, request.categoryId(), mode);
            for (Long shopId : eligible) {
                jdbc.update("""
                        INSERT INTO demand_request_recipients(request_id, shop_id)
                        VALUES (?, ?) ON CONFLICT DO NOTHING
                        """, id, shopId);
            }
        }
        return mine(id, customerId);
    }

    @Transactional(readOnly = true)
    public List<DemandView> mine(int page, int size) {
        Long customerId = currentUser.customerId();
        int limit = Math.min(Math.max(size, 1), 50);
        return jdbc.query("""
                SELECT * FROM demand_requests WHERE customer_id=?
                 ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> demand(rs.getLong("id"), rs.getString("description"),
                rs.getString("photo_url"), rs.getInt("quantity"), (Long) rs.getObject("category_id"),
                rs.getBigDecimal("budget"), local(rs.getTimestamp("required_by")),
                rs.getBigDecimal("radius_km"), rs.getString("preferred_mode"),
                effectiveStatus(rs.getString("status"), local(rs.getTimestamp("expires_at"))),
                local(rs.getTimestamp("expires_at")), local(rs.getTimestamp("created_at")),
                responses(rs.getLong("id"))), customerId, limit, Math.max(0, page) * limit);
    }

    @Transactional
    public DemandView close(long requestId, boolean cancel) {
        Long customerId = currentUser.customerId();
        int changed = jdbc.update("""
                UPDATE demand_requests SET status=?, closed_at=now(), updated_at=now()
                 WHERE id=? AND customer_id=? AND status='OPEN'
                """, cancel ? "CANCELLED" : "CLOSED", requestId, customerId);
        if (changed == 0) throw new ResourceNotFoundException("Open request not found.");
        return mine(requestId, customerId);
    }

    @Transactional(readOnly = true)
    public List<MerchantDemand> forCurrentShop(int page, int size) {
        long shopId = TenantContext.require().requireShopId();
        int limit = Math.min(Math.max(size, 1), 50);
        return jdbc.query("""
                SELECT d.*, r.id response_id, r.status response_status, r.price response_price,
                       r.quantity response_quantity, r.ready_minutes, r.commerce_mode response_mode,
                       r.merchant_note, r.created_at response_created
                  FROM demand_request_recipients dr
                  JOIN demand_requests d ON d.id=dr.request_id
                  LEFT JOIN demand_responses r ON r.request_id=d.id AND r.shop_id=dr.shop_id
                 WHERE dr.shop_id=? AND d.status='OPEN' AND d.expires_at > now()
                 ORDER BY d.created_at DESC, d.id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> {
            ResponseView response = rs.getObject("response_id") == null ? null : new ResponseView(
                    rs.getLong("response_id"), shopId, null, rs.getString("response_status"),
                    rs.getBigDecimal("response_price"), (Integer) rs.getObject("response_quantity"),
                    (Integer) rs.getObject("ready_minutes"), rs.getString("response_mode"),
                    rs.getString("merchant_note"), local(rs.getTimestamp("response_created")));
            return new MerchantDemand(rs.getLong("id"), rs.getString("description"),
                    rs.getString("photo_url"), rs.getInt("quantity"),
                    (Long) rs.getObject("category_id"), rs.getBigDecimal("budget"),
                    local(rs.getTimestamp("required_by")), rs.getBigDecimal("radius_km"),
                    rs.getString("preferred_mode"), local(rs.getTimestamp("expires_at")),
                    local(rs.getTimestamp("created_at")), response);
        }, shopId, limit, Math.max(0, page) * limit);
    }

    @Transactional
    public ResponseView respond(long requestId, ResponseRequest request) {
        long shopId = TenantContext.require().requireShopId();
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM demand_request_recipients dr JOIN demand_requests d ON d.id=dr.request_id
                   WHERE dr.request_id=? AND dr.shop_id=? AND d.status='OPEN' AND d.expires_at > now())
                """, Boolean.class, requestId, shopId);
        if (!Boolean.TRUE.equals(eligible)) throw new ResourceNotFoundException("Demand request not found.");
        String status = request == null ? "" : upper(request.status());
        if (!Set.of("AVAILABLE", "NOT_AVAILABLE").contains(status)) {
            throw new BadRequestException("Choose Available or Not available.");
        }
        CommerceMode mode = mode(request.commerceMode(), true);
        if ("AVAILABLE".equals(status) && request.price() != null && request.price().signum() <= 0) {
            throw new BadRequestException("Price must be greater than zero.");
        }
        if (request.quantity() != null && request.quantity() <= 0) {
            throw new BadRequestException("Quantity must be greater than zero.");
        }
        if (request.quantity() != null && request.quantity() > 10_000) {
            throw new BadRequestException("Quantity is too large.");
        }
        if (request.readyMinutes() != null
                && (request.readyMinutes() < 0 || request.readyMinutes() > 43_200)) {
            throw new BadRequestException("Ready time is invalid.");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO demand_responses
                    (request_id, shop_id, status, price, quantity, ready_minutes,
                     commerce_mode, merchant_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (request_id, shop_id) DO UPDATE SET
                    status=excluded.status, price=excluded.price, quantity=excluded.quantity,
                    ready_minutes=excluded.ready_minutes, commerce_mode=excluded.commerce_mode,
                    merchant_note=excluded.merchant_note, updated_at=now()
                RETURNING id
                """, Long.class, requestId, shopId, status, request.price(), request.quantity(),
                request.readyMinutes(), mode == null ? null : mode.name(), clean(request.note(), 500));
        return response(id, requestId, shopId);
    }

    private Set<Long> eligible(List<Long> shops, Long categoryId, CommerceMode mode) {
        String ids = String.join(",", java.util.Collections.nCopies(shops.size(), "?"));
        String sql = """
                SELECT s.id
                  FROM shops s
                 WHERE s.id IN (%s)
                   AND (?::bigint IS NULL OR EXISTS (
                       SELECT 1 FROM shop_categories sc
                        WHERE sc.shop_id=s.id AND sc.active=true
                          AND sc.global_category_id=?::bigint)
                     OR EXISTS (
                       SELECT 1 FROM shop_product_variants category_spv
                       JOIN product_variants v ON v.id=category_spv.product_variant_id
                       JOIN products p ON p.id=v.product_id
                        WHERE category_spv.shop_id=s.id AND category_spv.active=true
                          AND p.category_id=?::bigint))
                   AND (?::varchar IS NULL OR EXISTS (
                       SELECT 1 FROM shop_product_variants mode_spv
                        WHERE mode_spv.shop_id=s.id AND mode_spv.active=true
                          AND mode_spv.commerce_mode=?::varchar))
                """.formatted(ids);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(shops);
        args.add(categoryId); args.add(categoryId); args.add(categoryId);
        args.add(mode == null ? null : mode.name()); args.add(mode == null ? null : mode.name());
        return new java.util.LinkedHashSet<>(jdbc.queryForList(sql, Long.class, args.toArray()));
    }

    private DemandView mine(long id, long customerId) {
        return jdbc.query("""
                SELECT * FROM demand_requests WHERE id=? AND customer_id=?
                """, rs -> {
            if (!rs.next()) throw new ResourceNotFoundException("Demand request not found.");
            return demand(id, rs.getString("description"), rs.getString("photo_url"),
                    rs.getInt("quantity"), (Long) rs.getObject("category_id"),
                    rs.getBigDecimal("budget"), local(rs.getTimestamp("required_by")),
                    rs.getBigDecimal("radius_km"), rs.getString("preferred_mode"),
                    effectiveStatus(rs.getString("status"), local(rs.getTimestamp("expires_at"))),
                    local(rs.getTimestamp("expires_at")), local(rs.getTimestamp("created_at")),
                    responses(id));
        }, id, customerId);
    }

    private DemandView demand(Long id, String description, String photo, int quantity,
                              Long category, BigDecimal budget, LocalDateTime requiredBy,
                              BigDecimal radius, String mode, String status,
                              LocalDateTime expires, LocalDateTime created,
                              List<ResponseView> responses) {
        return new DemandView(id, description, photo, quantity, category, budget, requiredBy,
                radius, mode, status, expires, created, responses);
    }

    private List<ResponseView> responses(long requestId) {
        return jdbc.query("""
                SELECT r.*, s.display_name FROM demand_responses r
                  JOIN shops s ON s.id=r.shop_id WHERE r.request_id=?
                 ORDER BY r.created_at, r.id
                """, (rs, n) -> new ResponseView(rs.getLong("id"), rs.getLong("shop_id"),
                rs.getString("display_name"), rs.getString("status"), rs.getBigDecimal("price"),
                (Integer) rs.getObject("quantity"), (Integer) rs.getObject("ready_minutes"),
                rs.getString("commerce_mode"), rs.getString("merchant_note"),
                local(rs.getTimestamp("created_at"))), requestId);
    }

    private ResponseView response(long responseId, long requestId, long shopId) {
        return jdbc.query("""
                SELECT r.*, s.display_name FROM demand_responses r
                  JOIN shops s ON s.id=r.shop_id
                 WHERE r.id=? AND r.request_id=? AND r.shop_id=?
                """, rs -> {
            if (!rs.next()) throw new IllegalStateException("Saved response was not readable.");
            return new ResponseView(rs.getLong("id"), rs.getLong("shop_id"),
                    rs.getString("display_name"), rs.getString("status"),
                    rs.getBigDecimal("price"), (Integer) rs.getObject("quantity"),
                    (Integer) rs.getObject("ready_minutes"), rs.getString("commerce_mode"),
                    rs.getString("merchant_note"), local(rs.getTimestamp("created_at")));
        }, responseId, requestId, shopId);
    }

    private static CommerceMode mode(String raw, boolean optional) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CommerceMode.valueOf(upper(raw));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported commerce mode.");
        }
    }
    private static String safePhoto(String raw) {
        String value = clean(raw, 1000);
        if (value == null) return null;
        if (value.contains("..") || value.startsWith("/")
                || !(value.startsWith("catalog/")
                || CatalogUrlValidator.isAllowedImageUrl(value))) {
            throw new BadRequestException("Use a GP-STORE controlled image upload.");
        }
        return value;
    }
    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    private static double coarseCoordinate(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }
    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
    private static LocalDateTime local(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
    private static String effectiveStatus(String stored, LocalDateTime expires) {
        return "OPEN".equals(stored) && expires != null && !expires.isAfter(LocalDateTime.now())
                ? "EXPIRED" : stored;
    }
}
