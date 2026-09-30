package com.gpstore.platform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Customer notification history spans orders from multiple shops, so these
 * ownership-checked customer routes must not inherit whichever storefront the
 * customer is currently browsing.
 */
class CustomerNotificationTenantScopeTest {

    @Test
    void customerNotificationRoutesUseCrossShopReadScope() throws Exception {
        Method method = TenantContextFilter.class
                .getDeclaredMethod("customerNotificationPath", String.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(null, "/api/notifications/mine"));
        assertTrue((boolean) method.invoke(null, "/api/notifications/unread-count"));
        assertTrue((boolean) method.invoke(null, "/api/notifications/read-all"));
        assertTrue((boolean) method.invoke(null, "/api/notifications/42"));
        assertTrue((boolean) method.invoke(null, "/api/notifications/42/read"));

        // Admin/broadcast surfaces keep their existing authorization/scope.
        assertFalse((boolean) method.invoke(null, "/api/notifications/broadcast"));
        assertFalse((boolean) method.invoke(null, "/api/notifications"));
    }
}
