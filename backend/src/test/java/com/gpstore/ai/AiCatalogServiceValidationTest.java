package com.gpstore.ai;

import com.gpstore.exception.BadRequestException;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCatalogServiceValidationTest {
    @Mock private JdbcTemplate jdbc;
    @Mock private CurrentUser currentUser;
    @Mock private ProductService products;

    @Test
    void invalidAiPriceCannotBeSavedOrPublished() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(4L))).thenReturn(true);
        AiCatalogService service = new AiCatalogService(jdbc, currentUser, products);
        AiCatalogService.DraftInput invalid = input(
                new BigDecimal("100"), new BigDecimal("120"), null);

        TenantContext.runWithin(TenantScope.ofShop(2L), () ->
                assertThrows(BadRequestException.class, () -> service.update(8L, invalid)));
        verify(products, never()).createProduct(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void arbitraryRemoteImageCannotEnterAnAiDraft() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(4L))).thenReturn(true);
        AiCatalogService service = new AiCatalogService(jdbc, currentUser, products);
        AiCatalogService.DraftInput invalid = input(
                new BigDecimal("120"), new BigDecimal("100"),
                "https://tracking.example/merchant.png");

        TenantContext.runWithin(TenantScope.ofShop(2L), () ->
                assertThrows(BadRequestException.class, () -> service.update(8L, invalid)));
        verify(products, never()).createProduct(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void inactiveCategoryIsRejectedBeforeAnyPublication() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(4L))).thenReturn(false);
        AiCatalogService service = new AiCatalogService(jdbc, currentUser, products);

        TenantContext.runWithin(TenantScope.ofShop(2L), () ->
                assertThrows(BadRequestException.class, () -> service.update(
                        8L, input(new BigDecimal("120"), new BigDecimal("100"), null))));
        verify(products, never()).createProduct(
                org.mockito.ArgumentMatchers.any());
    }

    private static AiCatalogService.DraftInput input(
            BigDecimal mrp, BigDecimal sellingPrice, String imageUrl) {
        return new AiCatalogService.DraftInput(
                "Tata Salt", "Tata", null, 4L, "1 kg",
                1.0, "kg", mrp, sellingPrice, 10,
                "8901234567890", imageUrl, "ONLINE_PURCHASE");
    }
}
