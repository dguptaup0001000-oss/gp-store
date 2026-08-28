package com.gpstore.catalog;

import com.gpstore.exception.ConflictException;
import com.gpstore.upload.CatalogImageRefs;
import com.gpstore.upload.ImageKind;
import com.gpstore.upload.R2ObjectStorageService;
import com.gpstore.upload.UploadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Copies existing Cloudinary catalogue bytes into R2 and rewrites the
 * matching {@code image_url} columns. Does not delete Cloudinary objects.
 *
 * Operator-triggered only. This cloud agent cannot run it against production
 * without VPS R2 credentials.
 */
@Service
public class CatalogImageR2MigrationService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImageR2MigrationService.class);
    private static final String USER_AGENT = "GP-Store/1.0 (catalog image migration to R2)";
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final R2ObjectStorageService r2;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CatalogImageR2MigrationService(JdbcTemplate jdbc, R2ObjectStorageService r2) {
        this.jdbc = jdbc;
        this.r2 = r2;
    }

    public MigrationReport migrate(int requestedLimit) {
        if (!r2.isConfigured()) {
            throw new ConflictException(
                    "R2 is not configured. Set R2_* on the Hostinger VPS before migrating images.");
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        List<Row> rows = new ArrayList<>();
        rows.addAll(loadProductImages(limit));
        if (rows.size() < limit) {
            rows.addAll(loadSimple(
                    "SELECT id, image_url FROM product_variants "
                            + "WHERE image_url ILIKE '%res.cloudinary.com%' ORDER BY id LIMIT ?",
                    limit - rows.size(), ImageKind.PRODUCT, "product_variants"));
        }
        if (rows.size() < limit) {
            rows.addAll(loadSimple(
                    "SELECT id, image_url FROM categories "
                            + "WHERE image_url ILIKE '%res.cloudinary.com%' ORDER BY id LIMIT ?",
                    limit - rows.size(), ImageKind.CATEGORY, "categories"));
        }

        int migrated = 0;
        int failed = 0;
        int missing = 0;
        int skipped = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();

        for (Row row : rows) {
            try {
                byte[] bytes = download(row.url);
                if (bytes == null) {
                    missing++;
                    continue;
                }
                String contentType = UploadPolicy.detectContentType(bytes);
                if (contentType == null) {
                    skipped++;
                    continue;
                }
                if (bytes.length > row.kind.maxBytes()) {
                    skipped++;
                    continue;
                }
                String key = UploadPolicy.objectKey(row.kind, row.ownerId, contentType);
                r2.putBytes(key, contentType, bytes);
                String storedRef = CatalogImageRefs.storedRef(key);
                if (storedRef.length() > 500) {
                    failed++;
                    errors.add(row.table + "#" + row.id + " image ref too long");
                    continue;
                }
                int n = jdbc.update(
                        "UPDATE " + row.table + " SET image_url = ? WHERE id = ?",
                        storedRef, row.id);
                if (n == 1) {
                    migrated++;
                    updated++;
                    log.info("Migrated {}#{} to R2 (sha256={}, {} bytes).",
                            row.table, row.id, sha256(bytes), bytes.length);
                } else {
                    failed++;
                    errors.add(row.table + "#" + row.id + " update missed");
                }
            } catch (RuntimeException ex) {
                failed++;
                errors.add(row.table + "#" + row.id + " " + safeMessage(ex));
                log.warn("Cloudinary→R2 copy failed for {}#{}", row.table, row.id);
            }
        }

        return new MigrationReport(
                rows.size(), migrated, failed, missing, skipped, updated, errors);
    }

    private List<Row> loadProductImages(int limit) {
        return jdbc.query(
                "SELECT id, image_url, product_id, product_variant_id FROM product_images "
                        + "WHERE image_url ILIKE '%res.cloudinary.com%' ORDER BY id LIMIT ?",
                (rs, i) -> {
                    Long owner = rs.getObject("product_variant_id", Long.class);
                    if (owner == null) {
                        owner = rs.getObject("product_id", Long.class);
                    }
                    return new Row("product_images", rs.getLong("id"),
                            rs.getString("image_url"), ImageKind.PRODUCT, owner);
                },
                limit);
    }

    private List<Row> loadSimple(String sql, int limit, ImageKind kind, String table) {
        return jdbc.query(sql, (rs, i) -> new Row(
                table, rs.getLong("id"), rs.getString("image_url"), kind, rs.getLong("id")), limit);
    }

    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("download failed");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            return "";
        }
    }

    private static String safeMessage(Exception ex) {
        // Class name only: AWS/R2 error bodies can include access-key ids
        // or signed-URL query strings.
        return ex.getClass().getSimpleName();
    }

    public record MigrationReport(
            int totalImages,
            int migrated,
            int failed,
            int missing,
            int skipped,
            int databaseRecordsUpdated,
            List<String> errors) {}

    private record Row(String table, long id, String url, ImageKind kind, Long ownerId) {}
}
