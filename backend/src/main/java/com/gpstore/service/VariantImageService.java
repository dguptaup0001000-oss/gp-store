package com.gpstore.service;

import com.gpstore.entity.ProductImage;
import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.ProductImageRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.upload.CatalogImageCleanup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A variant's photos: front of the packet, back, side, ingredients.
 *
 * THE WHOLE LIST IS THE UNIT, and that is the design decision everything else
 * follows from. The admin screen sends the complete ordered list it wants the
 * variant to have, and this replaces what was there. It does not add one, or
 * remove one by id, or move one up.
 *
 * Why: the first image is the PRIMARY one, so order carries meaning, and
 * order is exactly what incremental operations get wrong. "Add", "delete" and
 * "reorder" as three endpoints means three ways for the client's idea of the
 * order to drift from the server's, and a screen that shows a different
 * primary photo from the one the customer sees. Sending the list you want is
 * one operation with one outcome, and re-sending it is harmless.
 *
 * WHAT IT IS NOT. This does not upload anything. Image bytes go from the
 * admin's phone to object storage with a short-lived signed URL - see
 * ImageUploadService. What arrives here is a list of HTTPS URLs.
 */
@Service
public class VariantImageService {

    /**
     * The most photos one variant may have.
     *
     * Five, because the brief says five, and because a grocery variant that
     * needs a sixth photo is a variant nobody is going to scroll. Enforced
     * here AND by a database trigger (see V22) - this endpoint is the only
     * writer today, and the trigger is what keeps that true tomorrow.
     */
    public static final int MAX_IMAGES_PER_VARIANT = 5;

    private final ProductImageRepository imageRepository;
    private final ProductVariantRepository variantRepository;
    private final CatalogImageCleanup imageCleanup;
    private final int maxUrlLength;

    public VariantImageService(ProductImageRepository imageRepository,
                               ProductVariantRepository variantRepository,
                               CatalogImageCleanup imageCleanup,
                               @Value("${catalog.image-url-max-length:500}") int maxUrlLength) {
        this.imageRepository = imageRepository;
        this.variantRepository = variantRepository;
        this.imageCleanup = imageCleanup;
        this.maxUrlLength = maxUrlLength;
    }

    /** One variant's photos in display order. First is primary. Never null. */
    @Transactional(readOnly = true)
    public List<String> imagesFor(Long variantId) {
        return imageRepository.findByProductVariantIdOrderBySortOrderAsc(variantId).stream()
                .map(ProductImage::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .toList();
    }

    /**
     * Replaces a variant's photos with exactly this list, in this order.
     *
     * @param urls the complete list the variant should have; empty clears it
     * @return the list as stored, which is what the caller should render
     */
    @CacheEvict(value = {"products", "productDetail", "categoryProducts", "newArrivals",
            "productSearch", "productFeed", "bestsellerTiles"}, allEntries = true)
    @Transactional
    public List<String> replaceImages(Long variantId, List<String> urls) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));

        List<String> previous = imagesFor(variantId);
        List<String> cleaned = clean(urls);

        if (cleaned.size() > MAX_IMAGES_PER_VARIANT) {
            throw new BadRequestException("A variant can have at most "
                    + MAX_IMAGES_PER_VARIANT + " photos. You sent " + cleaned.size() + ".");
        }

        // DELETE-THEN-INSERT, flushed between the two.
        //
        // Without the flush both statements go to the database at commit, in
        // Hibernate's own order - inserts before deletes - so re-sending a
        // list that keeps an existing URL would insert the duplicate first
        // and trip the five-image trigger from V22 on a list of five.
        imageRepository.deleteByProductVariantId(variantId);
        imageRepository.flush();

        List<ProductImage> rows = new ArrayList<>();
        for (int i = 0; i < cleaned.size(); i++) {
            ProductImage row = new ProductImage();
            row.setProductVariant(variant);
            // The product is set too, and deliberately. Deleting a product
            // must take its variants' photos with it, and the existing
            // ON DELETE CASCADE on product_id is what already does that -
            // leaving it null would make variant photos the one kind of row
            // that outlives its product.
            row.setProduct(variant.getProduct());
            row.setImageUrl(cleaned.get(i));
            // The index IS the order, and index 0 IS the primary image.
            row.setSortOrder(i);
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        imageRepository.saveAll(rows);

        // The variant's own imageUrl follows the primary photo.
        //
        // THIS IS THE BACKWARD-COMPATIBILITY HINGE. Every listing, every cart
        // line, every order item and every existing client reads
        // ProductVariant.imageUrl and knows nothing about galleries. Keeping
        // it equal to the first photo means the new feature shows up
        // everywhere the old field already appeared, with no client change and
        // no migration - and a variant whose photos are cleared keeps the
        // thumbnail it had rather than losing its picture entirely.
        if (!cleaned.isEmpty()) {
            variant.setImageUrl(cleaned.get(0));
            variantRepository.save(variant);
        }

        // Database already has the new list. Drop unused R2 objects only —
        // Cloudinary URLs are ignored by CatalogImageCleanup / R2 delete.
        imageCleanup.deleteRemovedAfterCommit(previous, cleaned);

        return cleaned;
    }

    /**
     * Trims, drops blanks, rejects anything too long, and removes duplicates
     * while keeping the first occurrence.
     *
     * De-duplication is here rather than left to the client because the same
     * photo picked twice is a real thing a person does, and two identical
     * thumbnails in a gallery reads as a bug in the shop rather than a slip
     * at the picker.
     */
    private List<String> clean(List<String> urls) {
        if (urls == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String url : urls) {
            if (url == null) {
                continue;
            }
            String trimmed = url.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > maxUrlLength) {
                throw new BadRequestException(
                        "One of those image links is too long (" + trimmed.length()
                                + " characters, limit " + maxUrlLength + ").");
            }
            if (!com.gpstore.catalog.CatalogUrlValidator.isAllowedImageUrl(trimmed)) {
                throw new BadRequestException(com.gpstore.catalog.CatalogUrlValidator.IMAGE_MESSAGE);
            }
            unique.add(trimmed);
        }
        return List.copyOf(unique);
    }
}
