package com.gpstore.security;

import com.gpstore.entity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * THE SIXTEEN RULES THE FINAL ARCHITECTURE RESTS ON, IN ONE PLACE.
 *
 * <p>GP-STORE has exactly four business roles - CUSTOMER shops, DELIVERY_BOY
 * delivers, ADMIN owns a shop, SUPER_ADMIN owns the platform - and sixteen
 * rules about them that must never quietly stop being true. They are spread
 * across a dozen test classes, because each is best proved where its fixture
 * lives: a rule about a rider's COD belongs beside a real delivery, not beside
 * a permission set.
 *
 * <p>WHAT THIS CLASS ADDS, AND WHY IT IS NOT A DUPLICATE. Spread out like
 * that, a rule can be lost by DELETION rather than by failure. Rename the
 * method, move it, drop it during a refactor, and nothing goes red - the rule
 * simply stops being checked, and the next person to read the suite has no way
 * to tell the difference between "proved elsewhere" and "not proved anywhere".
 * So every rule below is one test method that does two things:
 *
 * <ol>
 *   <li>asserts what it can assert cheaply and directly here - the permission
 *       algebra, which needs no Spring context and therefore cannot be skipped
 *       for being slow; and
 *   <li>names the end-to-end guard that proves it over real HTTP, and FAILS if
 *       that guard has gone. Not "a test exists somewhere" - that exact class
 *       and that exact method.
 * </ol>
 *
 * <p>A rule with no cheap algebraic half (nothing about a rider's COD shows up
 * in a permission set) is carried entirely by its named guard, which is
 * honest: this class then says where the proof is and breaks when it leaves.
 */
@DisplayName("The sixteen role rules")
class TheSixteenRoleRulesTest {

    private static Set<AdminPermission> of(Role role) {
        return RolePermissions.forRole(role);
    }

    /**
     * The end-to-end guard for a rule, asserted to still exist under that name.
     *
     * <p>Loaded by name rather than referenced as a class literal on purpose:
     * a compile-time reference would make this file fail to COMPILE if a guard
     * were deleted, which reads as a broken build rather than as a missing
     * security test. A named failure says what actually happened.
     */
    private static void provenEndToEndBy(String className, String methodName) {
        Class<?> guard;
        try {
            guard = Class.forName(className);
        } catch (ClassNotFoundException gone) {
            fail("THE END-TO-END GUARD FOR THIS RULE IS GONE: " + className
                    + " no longer exists. The rule it proved is part of the final role "
                    + "architecture. Restore it, or move its assertions somewhere and point "
                    + "this line at the new home - do not delete this line.");
            return;
        }
        boolean present = Arrays.stream(guard.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(methodName::equals);
        if (!present) {
            fail("THE END-TO-END GUARD FOR THIS RULE IS GONE: " + className + "."
                    + methodName + "() no longer exists, so this rule is no longer proved "
                    + "against a real request anywhere. Restore it, or point this line at its "
                    + "new home - do not delete this line.");
        }
    }

    // ---------------------------------------------------------- SUPER_ADMIN

    @Test
    @DisplayName("TEST 1: SUPER_ADMIN can access platform-wide resources")
    void test01() {
        assertThat(of(Role.SUPER_ADMIN)).contains(
                AdminPermission.PLATFORM_ADMIN,
                AdminPermission.PLATFORM_OBSERVABILITY);
        provenEndToEndBy("com.gpstore.platform.MarketplaceIdentityTest",
                "superAdminReachesPlatformWideResources");
        provenEndToEndBy("com.gpstore.security.StaffRoleAuthorizationTest",
                "superAdminReachesSystemSurface");
    }

    @Test
    @DisplayName("TEST 2: SUPER_ADMIN can manage any shop")
    void test02() {
        // TenantResolver grants marketplace-wide scope on this one permission,
        // and lets its holder name ANY shop in select(). Without it the
        // platform owner is resolved to a single shop like a shopkeeper.
        assertThat(of(Role.SUPER_ADMIN)).contains(AdminPermission.PLATFORM_ADMIN);
        provenEndToEndBy("com.gpstore.platform.MarketplaceIdentityTest",
                "superAdminCanManageAnyShop");
    }

    @Test
    @DisplayName("TEST 3: SUPER_ADMIN can manage any shop's workers")
    void test03() {
        assertThat(of(Role.SUPER_ADMIN)).contains(
                AdminPermission.DELIVERY_MANAGE, AdminPermission.DELIVERY_VIEW);
        provenEndToEndBy("com.gpstore.platform.MarketplaceIdentityTest",
                "superAdminCanManageAnyShopWorker");
    }

    // ---------------------------------------------------------------- ADMIN

    @Test
    @DisplayName("TEST 4: ADMIN can manage its own shop")
    void test04() {
        assertThat(of(Role.ADMIN)).contains(
                AdminPermission.CATALOG_MANAGE,
                AdminPermission.INVENTORY_MANAGE,
                AdminPermission.ORDERS_MANAGE,
                AdminPermission.PAYMENTS_MANAGE,
                AdminPermission.PAYMENTS_REFUND,
                AdminPermission.COUPONS_MANAGE,
                AdminPermission.CUSTOMERS_MANAGE);
        provenEndToEndBy("com.gpstore.security.StaffRoleAuthorizationTest",
                "adminReachesEverything");
        provenEndToEndBy("com.gpstore.platform.MarketplaceIdentityTest",
                "aSecondMerchantOperatesTheirOwnShop");
    }

    @Test
    @DisplayName("TEST 5: ADMIN can manage its own workers")
    void test05() {
        assertThat(of(Role.ADMIN)).contains(
                AdminPermission.DELIVERY_MANAGE, AdminPermission.DELIVERY_VIEW);
        provenEndToEndBy("com.gpstore.platform.ShopStaffAndRidersTest",
                "aShopMayHireAFreeAgent");
    }

    @Test
    @DisplayName("TEST 6: ADMIN cannot access another shop")
    void test06() {
        // NOT A PERMISSION QUESTION AT ALL, and that is worth stating: a shop
        // owner holds ORDERS_MANAGE outright. What stops them touching another
        // merchant's orders is the SCOPE the credential resolves to, which no
        // permission set can express. Carried entirely by its guards.
        provenEndToEndBy("com.gpstore.platform.MarketplaceIdentityTest",
                "merchantACannotSelectShopB");
        provenEndToEndBy("com.gpstore.platform.MarketplaceIdentityTest",
                "merchantACannotReadShopBProfile");
        provenEndToEndBy("com.gpstore.platform.CrossTenantApiAccessTest",
                "writingToAnotherShopsOrderOverHttp");
    }

    @Test
    @DisplayName("TEST 7: ADMIN cannot access another shop's workers")
    void test07() {
        provenEndToEndBy("com.gpstore.platform.MarketplaceIdentityTest",
                "superAdminCanManageAnyShopWorker");
        provenEndToEndBy("com.gpstore.platform.ShopStaffAndRidersTest",
                "aShopCannotPoachAnotherMerchantsStaff");
    }

    @Test
    @DisplayName("TEST 8: ADMIN cannot read platform metrics")
    void test08() {
        assertThat(of(Role.ADMIN))
                .as("a shop owner reading the marketplace's own traffic can estimate every "
                        + "other merchant's order volume")
                .doesNotContain(AdminPermission.PLATFORM_OBSERVABILITY)
                .doesNotContain(AdminPermission.PLATFORM_ADMIN);
        provenEndToEndBy("com.gpstore.platform.MarketplaceOversightTest",
                "aShopOwnerCannotOpenTheMarket");
        provenEndToEndBy("com.gpstore.security.TheGpStoreRoleModelTest$ShopAdmin",
                "isNotTheAppAdmin");
    }

    @Test
    @DisplayName("TEST 9: ADMIN cannot use the platform's actuator/management surface")
    void test09() {
        assertThat(of(Role.ADMIN)).doesNotContain(AdminPermission.PLATFORM_OBSERVABILITY);
        // /actuator/** is gated on PLATFORM_OBSERVABILITY in SecurityConfig.
        // adminReachesEverything asserts the refusal on a real request, and is
        // ALSO the regression guard that nothing else was taken away with it.
        provenEndToEndBy("com.gpstore.security.StaffRoleAuthorizationTest",
                "adminReachesEverything");
    }

    // --------------------------------------------------------- DELIVERY_BOY

    @Test
    @DisplayName("TEST 10: DELIVERY_BOY cannot administer anything")
    void test10() {
        assertThat(of(Role.DELIVERY_BOY))
                .as("a rider holding any admin permission is a rider who can administer a shop")
                .isEmpty();
        provenEndToEndBy("com.gpstore.security.StaffRoleAuthorizationTest",
                "riderStillLockedOut");
    }

    @Test
    @DisplayName("TEST 11: DELIVERY_BOY cannot access another worker's delivery")
    void test11() {
        provenEndToEndBy("com.gpstore.worker.WorkerDeliveryStatusTest",
                "anotherWorkersDeliveryIsNotFound");
        provenEndToEndBy("com.gpstore.worker.WorkerDeliveryStatusTest",
                "anotherWorkersDeliveryIsHiddenOnRead");
    }

    @Test
    @DisplayName("TEST 12: DELIVERY_BOY cannot modify another worker's COD")
    void test12() {
        provenEndToEndBy("com.gpstore.worker.WorkerDeliveryStatusTest",
                "anotherWorkerCannotCompleteCod");
    }

    // -------------------------------------------------------------- CUSTOMER

    @Test
    @DisplayName("TEST 13: CUSTOMER cannot access admin APIs")
    void test13() {
        assertThat(of(Role.CUSTOMER)).isEmpty();
        assertThat(RolePermissions.isStaff(Role.CUSTOMER)).isFalse();
        provenEndToEndBy("com.gpstore.security.AdminAuthorizationIntegrationTest",
                "adminNewOrdersSinceRejectsCustomerRole");
        provenEndToEndBy("com.gpstore.security.AdminAuthorizationIntegrationTest",
                "workerAdminRejectsCustomerRole");
        provenEndToEndBy("com.gpstore.security.StaffRoleAuthorizationTest",
                "customerStillLockedOut");
    }

    // ------------------------------------- what the client is never believed

    @Test
    @DisplayName("TEST 14: a client-supplied shopId cannot bypass tenant isolation")
    void test14() {
        provenEndToEndBy("com.gpstore.platform.TheTenantNeverComesFromTheRequestTest",
                "theShopHeaderIsCheckedRatherThanTrusted");
        provenEndToEndBy("com.gpstore.platform.TheTenantNeverComesFromTheRequestTest",
                "aQueryParameterCannotChooseTheShop");
        provenEndToEndBy("com.gpstore.platform.TheTenantNeverComesFromTheRequestTest",
                "selectionNarrowsButNeverGrants");
    }

    @Test
    @DisplayName("TEST 15: a client-supplied payment status cannot mark an order paid")
    void test15() {
        provenEndToEndBy("com.gpstore.payment.TheClientNeverDeclaresAnOrderPaidTest",
                "aBodyClaimingPaidIsIgnored");
        provenEndToEndBy("com.gpstore.payment.TheClientNeverDeclaresAnOrderPaidTest",
                "aBodyClaimingItsOwnTotalIsIgnored");
        provenEndToEndBy("com.gpstore.payment.TheClientNeverDeclaresAnOrderPaidTest",
                "aCustomerCannotConfirmTheirOwnUpiPayment");
    }

    // ----------------------------------- and no fifth role sneaking back in

    @Test
    @DisplayName("TEST 16: PLATFORM_ADMIN cannot become an independent privilege level")
    void test16() {
        assertThat(of(Role.PLATFORM_ADMIN))
                .as("PLATFORM_ADMIN is a legacy alias for SUPER_ADMIN, not a level - if these "
                        + "two sets differ in EITHER direction it has become a second authority")
                .isEqualTo(of(Role.SUPER_ADMIN));
        provenEndToEndBy("com.gpstore.security.TheGpStoreRoleModelTest$NoSecondAuthority",
                "isExactlyTheAppAdmin");
    }

    @Test
    @DisplayName("and there are still exactly four business roles")
    void noFifthBusinessRole() {
        // Every other Role constant is a SHOP role - a subset of ADMIN that a
        // merchant may hand to their own staff - and none of them may hold
        // anything that spans the marketplace. This is the check that catches
        // a new role added with a copied-and-pasted permission set.
        for (Role role : Role.values()) {
            if (role == Role.SUPER_ADMIN || role == Role.PLATFORM_ADMIN) {
                continue;
            }
            assertThat(of(role))
                    .as("%s is not the platform owner and must not span the marketplace", role)
                    .doesNotContain(
                            AdminPermission.PLATFORM_ADMIN,
                            AdminPermission.PLATFORM_OBSERVABILITY,
                            AdminPermission.CATALOG_DEFINE);
        }
    }
}
