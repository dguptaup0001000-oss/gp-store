package com.gpstore.service;

import com.gpstore.entity.ProductVariant;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ProductVariantImageUrlTest {

    private ProductVariantRepository repository;
    private ProductVariantService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductVariantRepository.class);
        service = new ProductVariantService(repository, mock(com.gpstore.upload.CatalogImageCleanup.class),
                mock(com.gpstore.catalog.shop.ShopCatalog.class));
    }

    @Test
    void createRejectsNonCloudinaryImageUrl() {
        ProductVariant variant = priced();
        variant.setImageUrl("https://evil.example/payload.jpg");
        assertThrows(BadRequestException.class, () -> service.saveProductVariant(variant, false));
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsLookalikeCloudinaryHost() {
        ProductVariant existing = priced();
        existing.setId(3L);
        existing.setImageUrl("https://res.cloudinary.com/demo/image/upload/v1/gp/ok.jpg");
        when(repository.findById(3L)).thenReturn(Optional.of(existing));

        ProductVariant updated = priced();
        updated.setImageUrl("https://res.cloudinary.com.evil.example/x.jpg");

        assertThrows(BadRequestException.class, () -> service.update(3L, updated, false));
        verify(repository, never()).save(any());
    }

    private static ProductVariant priced() {
        ProductVariant variant = new ProductVariant();
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(new BigDecimal("19.00"));
        variant.setMrp(new BigDecimal("20.00"));
        variant.setCostPrice(new BigDecimal("14.00"));
        variant.setAvailable(true);
        return variant;
    }
}
