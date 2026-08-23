package com.gpstore.security;

import com.gpstore.controller.AddressController;
import com.gpstore.controller.CartController;
import com.gpstore.dto.response.CartResponse;
import com.gpstore.entity.Address;
import com.gpstore.service.AddressService;
import com.gpstore.service.CartService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two admin listings that used to call findAll() with no bound.
 *
 * WHY THIS MATTERED MORE THAN IT LOOKS. Both endpoints are admin-only, so
 * this was never a way in for an attacker. It was worse in a quieter way: at
 * a hundred thousand customers, one admin opening a list would load every
 * address - or every cart, each dragging its items along - into a 512 MB
 * heap and take the whole application down. Customers browsing and checking
 * out at that moment go down with it. A self-inflicted outage needs no
 * attacker to be an outage.
 *
 * Verified through the controller with a captured Pageable rather than
 * against a database, because the property under test is "what does the
 * controller ASK the service for" - and proving that needs no rows at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnboundedQueryGuardTest {

    @Mock private AddressService addressService;
    @Mock private com.gpstore.service.DeliveryEstimateService deliveryEstimateService;
    @Mock private com.gpstore.security.CurrentUser currentUser;
    @InjectMocks private AddressController addressController;

    @Mock private CartService cartService;
    @InjectMocks private CartController cartController;

    private Pageable captureAddressPageable(int page, int size) {
        when(addressService.getAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<Address>of()));
        addressController.getAllAddresses(page, size);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(addressService).getAll(captor.capture());
        return captor.getValue();
    }

    private Pageable captureCartPageable(int page, int size) {
        // getAllCartResponses, not getAllCarts: the entity-to-DTO mapping
        // moved into the service so it happens while the session is still
        // open (see spring.jpa.open-in-view). The cap being asserted here is
        // unchanged - only which method the controller calls.
        when(cartService.getAllCartResponses(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<CartResponse>of()));
        cartController.getAllCarts(page, size);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(cartService).getAllCartResponses(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("an absurd page size is capped at 100, not honoured")
    void addressPageSizeIsCapped() {
        assertEquals(100, captureAddressPageable(0, Integer.MAX_VALUE).getPageSize(),
                "size=MAX_VALUE was passed through - the query is still unbounded in practice");
    }

    @Test
    @DisplayName("carts are capped the same way")
    void cartPageSizeIsCapped() {
        assertEquals(100, captureCartPageable(0, 1_000_000).getPageSize());
    }

    @Test
    @DisplayName("a sensible page size is honoured unchanged")
    void reasonableSizeIsNotAltered() {
        assertEquals(25, captureAddressPageable(0, 25).getPageSize());
        assertEquals(25, captureCartPageable(0, 25).getPageSize());
    }

    @Test
    @DisplayName("size=0 cannot produce a zero-size page request")
    void zeroSizeIsRejected() {
        // PageRequest.of throws on size < 1, so an unguarded size=0 is a 500
        // rather than a validation error. Clamped to 1.
        assertEquals(1, captureAddressPageable(0, 0).getPageSize());
        assertEquals(1, captureCartPageable(0, -5).getPageSize());
    }

    @Test
    @DisplayName("a negative page number cannot reach PageRequest")
    void negativePageIsClamped() {
        assertEquals(0, captureAddressPageable(-3, 20).getPageNumber());
        assertEquals(0, captureCartPageable(-1, 20).getPageNumber());
    }

    @Test
    @DisplayName("paging is sorted, so pages cannot repeat or skip rows")
    void pagingIsDeterministic() {
        // An unsorted paged query has NO defined order in Postgres. Without an
        // ORDER BY, page 2 may repeat rows from page 1 and silently omit
        // others - which for an abandonment report means acting on a customer
        // list that is quietly wrong rather than obviously broken.
        Sort addressSort = captureAddressPageable(0, 20).getSort();
        assertTrue(addressSort.isSorted(), "address paging is unsorted");
        assertNotNull(addressSort.getOrderFor("id"), "address paging must sort by the primary key");

        Sort cartSort = captureCartPageable(0, 20).getSort();
        assertTrue(cartSort.isSorted(), "cart paging is unsorted");
        assertNotNull(cartSort.getOrderFor("id"), "cart paging must sort by the primary key");
    }
}
