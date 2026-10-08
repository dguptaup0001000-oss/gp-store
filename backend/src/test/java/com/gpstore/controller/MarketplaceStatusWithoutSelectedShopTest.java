package com.gpstore.controller;

import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.platform.TenantScope;
import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceStatusWithoutSelectedShopTest {

    @AfterEach
    void clearTenantState() {
        TenantContext.clear();
        TenantDefaults.reset();
    }

    @Test
    void legacyStatusIsNeutralForMarketplaceWideReads() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION, () -> 1L);
        DeliveryScheduleService schedule = mock(DeliveryScheduleService.class);
        when(schedule.now()).thenReturn(java.time.Instant.parse("2030-01-01T00:00:00Z"));

        StoreStatusController controller = new StoreStatusController(schedule);
        ResponseEntity<StoreStatusResponse> response = TenantContext.runWithin(
                TenantScope.platform(), controller::status);

        assertEquals(200, response.getStatusCode().value());
        StoreStatusResponse body = response.getBody();
        assertNotNull(body);
        assertTrue(body.browsingOpen());
        assertTrue(body.acceptingOrders());
        assertEquals(null, body.mode());
        assertEquals(null, body.deliveryType());
        assertEquals(null, body.deliveryDate());
        assertEquals("Select a shop to see its delivery schedule.", body.message());
        verify(schedule).now();
        verify(schedule, never()).getStoreStatus();
        verify(schedule, never()).shopZone();
    }
}
