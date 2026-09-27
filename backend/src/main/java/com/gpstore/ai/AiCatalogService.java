package com.gpstore.ai;

import com.gpstore.dto.request.ProductCreateRequest;
import com.gpstore.dto.response.ProductResponse;
import com.gpstore.catalog.CatalogUrlValidator;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.TenantContext;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.ProductService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-assisted catalogue data remains a draft until this shop explicitly
 * approves it through {@link #approve}. No extraction path calls ProductService.
 */
@Service
public class AiCatalogService {
    private static final Pattern VOICE = Pattern.compile(
            "^(\\d+)\\s+(?:packet|pack|piece|pcs)?\\s*(.+?)(?:,|\\s)+(?:₹|rs\\.?|rupees?|रुपये)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:each|प्रति)?$",
            Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final ProductService products;

    public AiCatalogService(JdbcTemplate jdbc, CurrentUser currentUser, ProductService products) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.products = products;
    }

    public record StartRequest(String sourceType, String objectKey, String manualText) {}
    public record BatchApproveRequest(List<Long> draftIds) {}
    public record JobView(Long id, String sourceType, String status, String errorCode,
                          LocalDateTime createdAt, List<DraftView> drafts) {}
    public record DraftInput(String name, String brand, String description, Long categoryId,
                             String variantLabel, Double quantity, String unit,
                             BigDecimal mrp, BigDecimal sellingPrice, Integer stock,
                             String barcode, String imageUrl, String commerceMode) {}
    public record DraftView(Long id, Long jobId, String name, String brand, String description,
                            Long categoryId, String variantLabel, Double quantity, String unit,
                            BigDecimal mrp, BigDecimal sellingPrice, Integer stock,
                            String barcode, String imageUrl, String commerceMode,
                            BigDecimal confidence, String generatedFields,
                            String uncertainFields, String status, Long approvedProductId,
                            LocalDateTime createdAt) {}

    @Transactional
    public JobView start(StartRequest request) {
        long shopId = shopId();
        long userId = currentUser.customerId();
        String type = request == null ? "" : upper(request.sourceType());
        if (!Set.of("MANUAL_TEXT", "VOICE", "PRODUCT_PHOTO", "LABEL_PHOTO",
                "INVOICE", "SHELF_PHOTO", "PRICE_LIST", "CATALOGUE_PAGE", "BARCODE")
                .contains(type)) {
            throw new BadRequestException("Unsupported catalogue input type.");
        }
        String objectKey = controlledKey(request.objectKey());
        String text = clean(request.manualText(), 2000);
        if (Set.of("MANUAL_TEXT", "VOICE").contains(type) && text == null) {
            throw new BadRequestException("Enter or dictate the product details.");
        }
        if (!Set.of("MANUAL_TEXT", "VOICE").contains(type) && objectKey == null) {
            throw new BadRequestException("Upload the source through GP-STORE first.");
        }
        String initialStatus = Set.of("MANUAL_TEXT", "VOICE").contains(type)
                ? "PROCESSING" : "QUEUED";
        Long jobId = jdbc.queryForObject("""
                INSERT INTO ai_extraction_jobs
                    (shop_id, requested_by, source_type, object_key, manual_text, status)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, shopId, userId, type, objectKey, text, initialStatus);

        // Deterministic text extraction is fast and local. Media/document jobs
        // remain queued for the configured background provider; the request
        // never blocks on OCR or a remote model.
        if (text != null) {
            DraftInput extracted = extractText(text, type);
            createDraft(jobId, shopId, userId, extracted,
                    extracted.sellingPrice() == null ? new BigDecimal("0.45") : new BigDecimal("0.80"),
                    generated(extracted), uncertain(extracted));
            jdbc.update("""
                    UPDATE ai_extraction_jobs SET status='REVIEW_READY', completed_at=now(), updated_at=now()
                     WHERE id=? AND shop_id=?
                    """, jobId, shopId);
        }
        return job(jobId);
    }

    /**
     * Claims and processes one media/document job. The HTTP request only
     * enqueues; this worker path owns all expensive extraction work.
     *
     * When no media provider is configured it creates an explicit empty draft
     * with every required field marked uncertain. It never guesses and never
     * publishes, so merchants can still complete review manually.
     */
    @Transactional
    public boolean processNextQueuedJob() {
        java.util.Map<String, Object> claimed = jdbc.query("""
                WITH next_job AS (
                    SELECT id FROM ai_extraction_jobs
                     WHERE status='QUEUED' ORDER BY created_at, id
                     FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE ai_extraction_jobs j
                   SET status='PROCESSING', updated_at=now()
                  FROM next_job
                 WHERE j.id=next_job.id
                RETURNING j.id, j.shop_id, j.requested_by, j.source_type, j.object_key
                """, rs -> {
            if (!rs.next()) return null;
            java.util.Map<String, Object> row = new java.util.HashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("shopId", rs.getLong("shop_id"));
            row.put("userId", rs.getLong("requested_by"));
            row.put("type", rs.getString("source_type"));
            row.put("objectKey", rs.getString("object_key"));
            return row;
        });
        if (claimed == null) return false;
        long jobId = ((Number) claimed.get("id")).longValue();
        long shopId = ((Number) claimed.get("shopId")).longValue();
        long userId = ((Number) claimed.get("userId")).longValue();
        String type = (String) claimed.get("type");
        String objectKey = (String) claimed.get("objectKey");
        createDraft(jobId, shopId, userId,
                new DraftInput(null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                BigDecimal.ZERO, "", "name,brand,categoryId,sellingPrice,unit,commerceMode");
        jdbc.update("""
                UPDATE ai_extraction_jobs SET status='REVIEW_READY',
                    completed_at=now(), updated_at=now()
                 WHERE id=? AND status='PROCESSING'
                """, jobId);
        return true;
    }

    @Transactional(readOnly = true)
    public List<JobView> jobs(int page, int size) {
        long shopId = shopId();
        int limit = Math.min(Math.max(size, 1), 50);
        return jdbc.query("""
                SELECT * FROM ai_extraction_jobs WHERE shop_id=?
                 ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new JobView(rs.getLong("id"), rs.getString("source_type"),
                rs.getString("status"), rs.getString("error_code"),
                local(rs.getTimestamp("created_at")), drafts(rs.getLong("id"))),
                shopId, limit, Math.max(page, 0) * limit);
    }

    @Transactional
    public DraftView update(long id, DraftInput input) {
        long shopId = shopId();
        validate(input);
        int changed = jdbc.update("""
                UPDATE ai_catalog_drafts SET name=?, brand=?, description=?, category_id=?,
                    variant_label=?, quantity=?, unit=?, mrp=?, selling_price=?, stock=?,
                    barcode=?, image_url=?, commerce_mode=?, updated_at=now()
                 WHERE id=? AND shop_id=? AND status='REVIEW'
                """, clean(input.name(), 255), clean(input.brand(), 255), input.description(),
                input.categoryId(), clean(input.variantLabel(), 120), input.quantity(),
                clean(input.unit(), 40), input.mrp(), input.sellingPrice(), input.stock(),
                clean(input.barcode(), 120), controlledImage(input.imageUrl()),
                requiredMode(input.commerceMode()), id, shopId);
        if (changed == 0) throw new ResourceNotFoundException("Review draft not found.");
        return draft(id);
    }

    @Transactional
    public DraftView approve(long id) {
        long shopId = shopId();
        long userId = currentUser.customerId();
        DraftView draft = draft(id);
        if (!"REVIEW".equals(draft.status())) throw new BadRequestException("Draft was already reviewed.");
        DraftInput input = new DraftInput(draft.name(), draft.brand(), draft.description(),
                draft.categoryId(), draft.variantLabel(), draft.quantity(), draft.unit(), draft.mrp(),
                draft.sellingPrice(), draft.stock(), draft.barcode(), draft.imageUrl(),
                draft.commerceMode());
        validate(input);

        ProductCreateRequest request = new ProductCreateRequest();
        request.setName(input.name().trim());
        request.setBrand(input.brand());
        request.setDescription(input.description());
        request.setCategoryId(input.categoryId());
        request.setActive(true);
        ProductCreateRequest.FirstVariant first = new ProductCreateRequest.FirstVariant();
        first.setLabel(input.variantLabel());
        first.setQuantity(input.quantity());
        first.setUnit(input.unit());
        first.setMrp(input.mrp());
        first.setSellingPrice(input.sellingPrice());
        first.setStock(input.stock() == null ? 0 : input.stock());
        first.setBarcode(input.barcode());
        first.setImageUrl(input.imageUrl());
        first.setCommerceMode(requiredMode(input.commerceMode()));
        first.setAvailable(true);
        request.setFirstVariant(first);
        ProductResponse published = products.createProduct(request);
        jdbc.update("""
                UPDATE ai_catalog_drafts SET status='APPROVED', approved_product_id=?,
                    approved_by=?, approved_at=now(), updated_at=now(),
                    approved_payload=jsonb_build_object(
                      'name', name, 'brand', brand, 'description', description,
                      'categoryId', category_id, 'variantLabel', variant_label,
                      'quantity', quantity, 'unit', unit, 'mrp', mrp,
                      'sellingPrice', selling_price, 'stock', stock,
                      'barcode', barcode, 'imageUrl', image_url,
                      'commerceMode', commerce_mode)
                 WHERE id=? AND shop_id=? AND status='REVIEW'
                """, published.getId(), userId, id, shopId);
        return draft(id);
    }

    @Transactional
    public DraftView reject(long id) {
        long shopId = shopId();
        int changed = jdbc.update("""
                UPDATE ai_catalog_drafts SET status='REJECTED', updated_at=now()
                 WHERE id=? AND shop_id=? AND status='REVIEW'
                """, id, shopId);
        if (changed == 0) throw new ResourceNotFoundException("Review draft not found.");
        return draft(id);
    }

    @Transactional
    public List<DraftView> approveBatch(BatchApproveRequest request) {
        if (request == null || request.draftIds() == null || request.draftIds().isEmpty()) {
            throw new BadRequestException("Choose at least one reviewed draft.");
        }
        List<Long> ids = request.draftIds().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(51)
                .toList();
        if (ids.isEmpty() || ids.size() > 50) {
            throw new BadRequestException("Approve between 1 and 50 drafts at a time.");
        }
        java.util.ArrayList<DraftView> approved = new java.util.ArrayList<>(ids.size());
        for (Long id : ids) {
            approved.add(approve(id));
        }
        return List.copyOf(approved);
    }

    private Long createDraft(Long jobId, long shopId, long userId, DraftInput input,
                             BigDecimal confidence, String generated, String uncertain) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ai_catalog_drafts
                    (job_id, shop_id, requested_by, name, brand, description, category_id,
                     variant_label, quantity, unit, mrp, selling_price, stock, barcode,
                     image_url, commerce_mode, confidence, generated_fields, uncertain_fields)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, jobId, shopId, userId, input.name(), input.brand(),
                input.description(), input.categoryId(), input.variantLabel(), input.quantity(),
                input.unit(), input.mrp(), input.sellingPrice(), input.stock(), input.barcode(),
                input.imageUrl(), input.commerceMode(), confidence, generated, uncertain);
        jdbc.update("""
                UPDATE ai_catalog_drafts SET original_payload=jsonb_build_object(
                    'name', name, 'brand', brand, 'description', description,
                    'categoryId', category_id, 'variantLabel', variant_label,
                    'quantity', quantity, 'unit', unit, 'mrp', mrp,
                    'sellingPrice', selling_price, 'stock', stock,
                    'barcode', barcode, 'imageUrl', image_url,
                    'commerceMode', commerce_mode)
                 WHERE id=? AND shop_id=?
                """, id, shopId);
        return id;
    }

    private DraftInput extractText(String text, String type) {
        Matcher voice = VOICE.matcher(text.trim());
        if (voice.matches()) {
            return new DraftInput(clean(voice.group(2), 255), null, null, null,
                    null, null, null, null, new BigDecimal(voice.group(3)),
                    Integer.valueOf(voice.group(1)), null, null, "ONLINE_PURCHASE");
        }
        return new DraftInput(clean(text, 255), null, null, null, null, null,
                null, null, null, null, null, null, "ONLINE_PURCHASE");
    }

    private void validate(DraftInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("Product name is required.");
        }
        if (input.categoryId() == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM categories WHERE id=? AND active=true)",
                Boolean.class, input.categoryId()))) {
            throw new BadRequestException("Choose a valid category.");
        }
        if (input.sellingPrice() == null || input.sellingPrice().signum() <= 0) {
            throw new BadRequestException("Selling price must be greater than zero.");
        }
        if (input.mrp() != null && input.mrp().signum() < 0) {
            throw new BadRequestException("MRP cannot be negative.");
        }
        if (input.mrp() != null && input.sellingPrice().compareTo(input.mrp()) > 0) {
            throw new BadRequestException("Selling price cannot exceed MRP.");
        }
        if (input.stock() != null && input.stock() < 0) {
            throw new BadRequestException("Stock cannot be negative.");
        }
        requiredMode(input.commerceMode());
    }

    private JobView job(long id) {
        long shopId = shopId();
        return jdbc.query("""
                SELECT * FROM ai_extraction_jobs WHERE id=? AND shop_id=?
                """, rs -> {
            if (!rs.next()) throw new ResourceNotFoundException("Extraction job not found.");
            return new JobView(id, rs.getString("source_type"), rs.getString("status"),
                    rs.getString("error_code"), local(rs.getTimestamp("created_at")), drafts(id));
        }, id, shopId);
    }

    private List<DraftView> drafts(long jobId) {
        long shopId = shopId();
        return jdbc.query("""
                SELECT * FROM ai_catalog_drafts WHERE job_id=? AND shop_id=? ORDER BY id
                """, (rs, n) -> mapDraft(rs), jobId, shopId);
    }

    private DraftView draft(long id) {
        long shopId = shopId();
        return jdbc.query("SELECT * FROM ai_catalog_drafts WHERE id=? AND shop_id=?", rs -> {
            if (!rs.next()) throw new ResourceNotFoundException("Catalogue draft not found.");
            return mapDraft(rs);
        }, id, shopId);
    }

    private static DraftView mapDraft(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DraftView(rs.getLong("id"), (Long) rs.getObject("job_id"), rs.getString("name"),
                rs.getString("brand"), rs.getString("description"), (Long) rs.getObject("category_id"),
                rs.getString("variant_label"), (Double) rs.getObject("quantity"), rs.getString("unit"),
                rs.getBigDecimal("mrp"), rs.getBigDecimal("selling_price"),
                (Integer) rs.getObject("stock"), rs.getString("barcode"), rs.getString("image_url"),
                rs.getString("commerce_mode"), rs.getBigDecimal("confidence"),
                rs.getString("generated_fields"), rs.getString("uncertain_fields"),
                rs.getString("status"), (Long) rs.getObject("approved_product_id"),
                local(rs.getTimestamp("created_at")));
    }

    private long shopId() {
        return TenantContext.require().requireShopId();
    }
    private static String generated(DraftInput input) {
        return input.sellingPrice() == null ? "name,commerceMode" : "name,sellingPrice,stock,commerceMode";
    }
    private static String uncertain(DraftInput input) {
        java.util.ArrayList<String> fields = new java.util.ArrayList<>();
        if (input.categoryId() == null) fields.add("categoryId");
        if (input.sellingPrice() == null) fields.add("sellingPrice");
        if (input.brand() == null) fields.add("brand");
        return String.join(",", fields);
    }
    private static String requiredMode(String raw) {
        try {
            return com.gpstore.catalog.shop.CommerceMode.valueOf(upper(raw)).name();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Choose how customers obtain this item.");
        }
    }
    private static String controlledKey(String raw) {
        String key = clean(raw, 1000);
        if (key == null) return null;
        if (key.contains("..") || key.startsWith("/") || !key.startsWith("catalog/")) {
            throw new BadRequestException("Use a GP-STORE controlled upload.");
        }
        return key;
    }
    private static String controlledImage(String raw) {
        String value = clean(raw, 1000);
        if (value == null) return null;
        if (!(value.startsWith("catalog/") || CatalogUrlValidator.isAllowedImageUrl(value))) {
            throw new BadRequestException("Use a GP-STORE controlled image.");
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
    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
