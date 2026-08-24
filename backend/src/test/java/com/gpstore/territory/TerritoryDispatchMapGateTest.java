package com.gpstore.territory;

import com.gpstore.entity.AssignmentReason;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Territory-aware dispatch must not run until at least one outline is mapped.
 *
 * A subzone row with a primary rider is not enough. mappedTerritoryCount()
 * only counts polygons the resolver can parse. Using the ladder on an empty
 * drawable map would look like a finished territory system while every
 * address still fails closed.
 *
 * Own Spring context (unique property below) so replacing TerritoryResolver
 * does not leak into TerritoryDispatchTest, which needs the real map.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        "gpstore.test.context=territory-dispatch-map-gate"
})
class TerritoryDispatchMapGateTest {

    @MockitoBean private TerritoryResolver resolver;
    @Autowired private TerritoryDispatchService dispatch;

    @Test
    @DisplayName("mappedTerritoryCount=0 does not assign the primary, even if one is set")
    void emptyDrawableMapDoesNotUseTheTerritoryLadder() {
        when(resolver.mappedTerritoryCount()).thenReturn(0);

        DeliveryPartner primary = new DeliveryPartner();
        primary.setId(99L);
        primary.setName("Would-be primary");
        primary.setAvailable(true);
        primary.setActive(true);

        DeliverySubzone subzone = new DeliverySubzone();
        subzone.setId(7L);
        subzone.setCode("Z7B");
        subzone.setName("Not yet drawn");
        subzone.setPrimaryPartner(primary);

        var decision = dispatch.chooseFor(subzone, 28.61, 77.21);

        assertFalse(decision.hasPartner(),
                "a primary on a subzone with no mapped outlines must not be chosen");
        assertEquals(AssignmentReason.FALLBACK, decision.reason());
        assertTrue(decision.explanation().contains("mappedTerritoryCount=0"),
                "the explanation must name the gate; was: " + decision.explanation());
    }

    @Test
    @DisplayName("mappedTerritoryCount=0 also gates a null subzone, with the same reason")
    void emptyDrawableMapGatesEvenWhenTheAddressHasNoSubzone() {
        when(resolver.mappedTerritoryCount()).thenReturn(0);

        var decision = dispatch.chooseFor(null, 28.61, 77.21);

        assertFalse(decision.hasPartner());
        assertEquals(AssignmentReason.FALLBACK, decision.reason());
        assertTrue(decision.explanation().contains("mappedTerritoryCount=0"),
                "an empty map is not the same as a hole in a finished map; was: "
                        + decision.explanation());
    }
}
