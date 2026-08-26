package com.gpstore.controller;

import com.gpstore.dto.response.StoreInfoResponse;
import com.gpstore.payment.gateway.CashfreeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreInfoControllerTest {

    @Test
    void placeholdersAreStrippedAndGatewaysStayOffWithoutCredentials() {
        CashfreeProperties cashfree = new CashfreeProperties();
        StoreInfoController controller = new StoreInfoController(
                "+91XXXXXXXXXX",
                "",
                "support@example.com",
                "https://example.com/help",
                "yourstorename@upi",
                cashfree);

        StoreInfoResponse info = controller.getStoreInfo();
        assertEquals("", info.getSupportPhone());
        assertEquals("", info.getSupportWhatsapp());
        assertEquals("", info.getSupportEmail());
        assertEquals("", info.getSupportUrl());
        assertFalse(info.isOnlinePaymentEnabled());
        assertFalse(info.isUpiConfigured());
    }

    @Test
    void realContactsAndConfiguredUpiAreReturned() {
        CashfreeProperties cashfree = new CashfreeProperties();
        cashfree.setAppId("cf_test_app");
        cashfree.setSecretKey("cf_test_secret_not_a_placeholder");
        StoreInfoController controller = new StoreInfoController(
                "+919876543210",
                "+919876543210",
                "hello@gpstore.co.in",
                "https://gpstore.co.in/help",
                "shop@okaxis",
                cashfree);

        StoreInfoResponse info = controller.getStoreInfo();
        assertEquals("+919876543210", info.getSupportPhone());
        assertEquals("hello@gpstore.co.in", info.getSupportEmail());
        assertEquals("https://gpstore.co.in/help", info.getSupportUrl());
        assertTrue(info.isOnlinePaymentEnabled());
        assertTrue(info.isUpiConfigured());
    }
}
