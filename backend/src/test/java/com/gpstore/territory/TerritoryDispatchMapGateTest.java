package com.gpstore.territory;

import com.gpstore.entity.AssignmentReason;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.SubzoneBackupPartnerRepository;
import com.gpstore.service.DeliveryEstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Territory-aware dispatch must not run until at least one outline is mapped.
 *
 * A subzone row with a primary rider is not enough. mappedTerritoryCount()
 * only counts polygons the resolver can parse. Using the ladder on an empty
 * drawable map would look like a finished territory system while every
 * address still fails closed.
 *
 * This is a constructor unit test on purpose. A @SpringBootTest that replaced
 * TerritoryResolver with @MockitoBean shared enough of the cached application
 * context that TerritoryDispatchTest saw a mock map on CI and picked the
 * wrong rider. The gate does not need a database.
 */
@ExtendWith(MockitoExtension.class)
class TerritoryDispatchMapGateTest {

    @Mock private DeliverySubzoneRepository subzoneRepository;
    @Mock private SubzoneBackupPartnerRepository backupRepository;
    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryEstimateService estimateService;
    @Mock private TerritoryResolver resolver;

    private TerritoryDispatchService dispatch;

    @BeforeEach
    void buildTheService() {
        dispatch = new TerritoryDispatchService(
                subzoneRepository,
                backupRepository,
                deliveryRepository,
                estimateService,
                resolver,
                true,
                4.0,
                0.8);
    }

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
        verifyNoInteractions(subzoneRepository, backupRepository, deliveryRepository);
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
        verifyNoInteractions(subzoneRepository, backupRepository, deliveryRepository);
    }
}
