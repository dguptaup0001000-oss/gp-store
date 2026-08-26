package com.gpstore.controller;

import com.gpstore.config.PageRequests;
import com.gpstore.dto.response.InventoryResponse;
import com.gpstore.entity.Coupon;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CouponService;
import com.gpstore.service.DeliveryPartnerService;
import com.gpstore.service.InventoryService;
import com.gpstore.service.OrderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4/18: every list endpoint is supposed to cap page size server-side
 * (Math.min(size, 100) is the established pattern across controllers) so a
 * client asking for size=100000 can never force a full-table dump back out.
 * These are plain controller unit tests (mocked service layer) - no DB
 * needed, since the capping happens entirely in the controller before the
 * service is ever called, and the only thing under test is what Pageable
 * actually gets passed down.
 */
@ExtendWith(MockitoExtension.class)
class PaginationCapTest {

    @Mock private OrderService orderService;
    @Mock private CurrentUser currentUser;
    @Mock private InventoryService inventoryService;
    @Mock private CouponService couponService;
    @Mock private DeliveryPartnerService deliveryPartnerService;

    private OrderController orderController;
    private InventoryController inventoryController;
    private CouponController couponController;
    private DeliveryPartnerController deliveryPartnerController;

    @BeforeEach
    void setUp() {
        orderController = new OrderController(orderService, currentUser);
        inventoryController = new InventoryController(inventoryService);
        couponController = new CouponController(couponService);
        deliveryPartnerController = new DeliveryPartnerController(deliveryPartnerService, currentUser);
    }

    @Test
    void getMyOrdersCapsRequestedPageSizeAt100() {
        when(currentUser.customerId()).thenReturn(1L);
        when(orderService.getMyOrders(eq(1L), any())).thenReturn(Page.empty());

        orderController.getMyOrders(0, 100_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).getMyOrders(eq(1L), captor.capture());
        assertEquals(100, captor.getValue().getPageSize(),
                "size=100000 must be capped to 100, never accepted as-is - this is what stops a client dumping every order in one call");
    }

    @Test
    void getAllOrdersForAdminCapsRequestedPageSizeAt100() {
        when(orderService.getAllOrdersForAdmin(any())).thenReturn(Page.empty());

        orderController.getAllOrdersForAdmin(0, Integer.MAX_VALUE);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).getAllOrdersForAdmin(captor.capture());
        assertEquals(100, captor.getValue().getPageSize(),
                "Integer.MAX_VALUE must still be capped to 100, not passed straight through to the query");
    }

    @Test
    void inventoryGetAllCapsRequestedPageSizeAt100() {
        when(inventoryService.getAll(any())).thenReturn(Page.<InventoryResponse>empty());

        inventoryController.getAll(0, 5000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(inventoryService).getAll(captor.capture());
        assertEquals(100, captor.getValue().getPageSize(),
                "size=5000 must be capped to 100, same as every other paginated admin list");
    }

    @Test
    void reasonableRequestedPageSizeIsRespectedAsIs() {
        when(currentUser.customerId()).thenReturn(1L);
        when(orderService.getMyOrders(eq(1L), any())).thenReturn(Page.empty());

        orderController.getMyOrders(0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).getMyOrders(eq(1L), captor.capture());
        assertEquals(20, captor.getValue().getPageSize(),
                "capping should only kick in above the limit - a normal page size shouldn't be silently altered");
    }

    @Test
    void adminCouponListCapsRequestedPageSizeAt100() {
        when(couponService.getAllCoupons(any())).thenReturn(java.util.List.<Coupon>of());

        couponController.getAllCoupons(0, 50_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(couponService).getAllCoupons(captor.capture());
        assertEquals(PageRequests.MAX_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void adminDeliveryPartnerListCapsRequestedPageSizeAt100() {
        when(deliveryPartnerService.getAll(any())).thenReturn(java.util.List.<DeliveryPartner>of());

        deliveryPartnerController.getAll(0, Integer.MAX_VALUE);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(deliveryPartnerService).getAll(captor.capture());
        assertEquals(PageRequests.MAX_PAGE_SIZE, captor.getValue().getPageSize());
    }
}
