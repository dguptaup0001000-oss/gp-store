package com.gpstore.demand;

import com.gpstore.exception.BadRequestException;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemandNetworkValidationTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private CurrentUser currentUser;
    @Mock private ShopDiscovery discovery;

    @Test
    void demandPhotosMustUseControlledCatalogueStorage() {
        when(currentUser.customerId()).thenReturn(7L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L))).thenReturn(0);
        DemandNetwork demand = new DemandNetwork(jdbc, currentUser, discovery);

        DemandNetwork.CreateRequest request = new DemandNetwork.CreateRequest(
                "tractor brake pad", "https://tracking.example/customer.png", 1,
                null, null, null, 22.3, 78.4, BigDecimal.valueOf(8), null);

        assertThrows(BadRequestException.class, () -> demand.create(request));
    }

    @Test
    void invalidCoordinatesAreRejectedBeforeDiscovery() {
        when(currentUser.customerId()).thenReturn(7L);
        DemandNetwork demand = new DemandNetwork(jdbc, currentUser, discovery);
        DemandNetwork.CreateRequest request = new DemandNetwork.CreateRequest(
                "tractor brake pad", null, 1, null, null, null,
                Double.NaN, 78.4, BigDecimal.valueOf(8), null);

        assertThrows(BadRequestException.class, () -> demand.create(request));
    }

    @Test
    void merchantCannotSendAnInvalidReadyTime() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(44L), eq(9L)))
                .thenReturn(true);
        DemandNetwork demand = new DemandNetwork(jdbc, currentUser, discovery);
        DemandNetwork.ResponseRequest response = new DemandNetwork.ResponseRequest(
                "AVAILABLE", BigDecimal.valueOf(850), 1, -1,
                "VISIT_TO_BUY", null);

        TenantContext.runWithin(TenantScope.ofShop(9L), () ->
                assertThrows(BadRequestException.class, () -> demand.respond(44L, response)));
    }
}
